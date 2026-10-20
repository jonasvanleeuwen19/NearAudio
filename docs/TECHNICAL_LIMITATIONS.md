# Technical constraints and design decisions

Status of this document: these are constraints established from knowledge of the public Android
APIs. The development environment used to write this code had **no network access and no Android
SDK**, so nothing was checked against live documentation or compiled/run. Re-verify before relying
on any claim, especially version-specific behaviour.

## Why audio does not travel over Bluetooth

| Option | Finding |
|---|---|
| A2DP source -> another phone | Phones act as A2DP *source* to headphones and *sink* only on few vendor builds. There is no public API for a third-party app to make a phone a sink for another phone's stream. |
| Bluetooth Classic RFCOMM audio | Possible with public sockets (`BluetoothSocket`), but ~1-2 Mbit/s real throughput, one point-to-point link per recipient, poor range/latency stability, and needs `BLUETOOTH_CONNECT/SCAN/ADVERTISE` permissions. Raw PCM (1.5 Mbit/s) does not fit reliably; a codec (extra dependency, latency) would be required. |
| LE Audio / Auracast broadcast | Android 13+ exposes broadcast *source/assistant* APIs mainly to system components and privileged/OEM code; there is no public API for an ordinary app to broadcast arbitrary captured audio, and it requires LE Audio hardware on both ends. |

Therefore **audio is sent over the local IP network** (TCP over the existing Wi-Fi or a phone's
hotspot). Bluetooth is **not used at all**, including for discovery: Wi-Fi is needed for the
audio anyway, and NSD discovery needs no dangerous permissions, which keeps the permission list
minimal. Wi-Fi Direct was not implemented (it adds permissions and a connection UX for little gain
over a shared Wi-Fi/hotspot).

The recipient's Bluetooth earbuds work normally: the receiving phone plays the stream through its
own audio output, and Android routes that to whatever is connected.

## Capture

* `MediaProjection` + `AudioPlaybackCaptureConfiguration` (API 29+, hence `minSdk 29`).
* Only usages MEDIA, GAME and UNKNOWN are captured. Apps that set `allowAudioPlaybackCapture=false`,
  DRM-protected playback and some system sounds are excluded by Android. Such sources produce
  silence; the app shows a warning after 5 s of silence but cannot tell "nothing playing" from
  "blocked". No attempt is made to bypass these restrictions.
* `AudioRecord` needs `RECORD_AUDIO` even for playback capture. The microphone is not used.
* The system shows the MediaProjection consent dialog (screen-capture wording). The app never
  captures video. On Android 14+ the user may pick "a single app", which limits what audio is captured.
* A foreground service (types `mediaProjection` / `mediaPlayback`) with a Stop action keeps
  sharing/receiving alive in the background.
* The source phone keeps playing the audio locally too (capture does not mute it).

## Transport and security

* NSD (`_nearaudio._tcp.`) for discovery/advertising, TCP for the session, versioned binary framing
  (`transport/Protocol.kt`), max payload 16 KiB.
* Handshake: ephemeral ECDH P-256 with hash commitment of the sender key, a 6-digit short
  authentication string both users compare, and explicit approval on the receiver. Audio frames are
  AES-128-GCM with a strictly increasing counter (replay protection). Without the user comparing
  the code, an active attacker on the same network could still impersonate; this is the only
  authentication available without accounts or pre-shared secrets.
* Anyone on the same network can open a connection to a listening receiver and cause an approval
  prompt (it can be declined; only one at a time is handled, others get "busy").
* Format: PCM 16-bit, 48 kHz, stereo, 20 ms frames (~1.5 Mbit/s per recipient). Sender queue per
  recipient: 15 frames (300 ms), drop-oldest. Receiver queue: 10 frames, drop-oldest.
* Expected end-to-end latency is on the order of 150-400 ms plus Bluetooth headphone latency
  (often 100-250 ms more). **There is no video synchronisation**; lip-sync is not guaranteed and
  was not measured.
* Max 4 recipients (`RecipientTable.DEFAULT_MAX_RECIPIENTS`). This is a conservative guess, not a
  measured limit.

## Network caveats

* Both phones must be on one network that allows device-to-device traffic and multicast DNS.
  Many guest/public/enterprise Wi-Fi networks use client isolation and will not work; a phone
  hotspot generally works.
* Android 16 introduced opt-in "local network" access protection and later releases may require a
  runtime permission for apps targeting newer SDKs. This app targets API 35 and has not been
  checked against that change (not verified).
* Discovery and listening stop when the app leaves the foreground unless a session is active.

## Offline mode (Bluetooth LE)

* Wi-Fi Direct was implemented in v0.2 and proved unusable in testing; it was removed.
* Discovery: listener advertises service UUID + (scan response) manufacturer data `[ver][PSM][id:4][alias]`.
  Sharer scans for the UUID. Classic Bluetooth discovery cannot identify app instances, and the classic
  address cannot be derived from a BLE advertisement, so RFCOMM is not an option.
* Transport: `createInsecureL2capChannel(psm)` (API 29+) = LE connection-oriented channel, no pairing;
  security is our own handshake + SAS + AES-GCM. `BluetoothSocket` has no read timeout, so a timer closes
  the socket on stalled reads.
* Bandwidth: practical LE L2CAP throughput varies (roughly 100 kbit/s to over 1 Mbit/s depending on
  phones, PHY and connection interval; Android gives no API to tune it for L2CAP). PCM (1.5 Mbit/s)
  cannot fit, so IMA ADPCM is used (`audio/AudioFormats.kt`): 24 kHz stereo ~195 kbit/s or 16 kHz mono
  ~66 kbit/s. Expect audible hiss/lower bandwidth compared with Wi-Fi. If the link is too slow the
  per-recipient queue drops audio (dropouts).
* Scanning is stopped when connecting (it competes for airtime). Bluetooth earbuds on the receiver share
  the same radio and may reduce throughput.
* **Not verified on any device.** Number of simultaneous recipients is limited by the Bluetooth controller.

## Playback smoothness (v0.3)

Findings from a two-phone test (stutter + delay): the old receiver started with ~60 ms of lead and the
TCP send buffer could hold seconds of audio. Now: jitter buffer target 80/140/240 ms, rebuffer on
underrun, skip-ahead when the queue exceeds twice the target, 32 KiB send / 64 KiB receive socket
buffers, URGENT_AUDIO thread priority, partial wake lock and Wi-Fi low-latency lock during sessions.
Not re-measured after these changes.
