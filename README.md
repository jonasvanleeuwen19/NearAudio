# NearAudio

Lightweight, open-source Android app that lets several nearby phones hear the **same audio at the
same time**: play a film on your phone, friends connect from their phones and listen through their
own speakers or Bluetooth earbuds.

> **Status: untested code.** This repository was written in an environment without Android SDK,
> Gradle or network access. It has **not been compiled, unit-tested, run on an emulator or tested
> on real phones.** Expect to fix compile errors on first build. See "Verification status".

## How it works (honest transport description)

* **Audio is sent over your local Wi-Fi network (or a phone hotspot) using TCP, encrypted with
  AES-GCM. Bluetooth is not used.** Public Android APIs do not let an ordinary app stream audio to
  other phones over Bluetooth (see [docs/TECHNICAL_LIMITATIONS.md](docs/TECHNICAL_LIMITATIONS.md)).
* Discovery uses Android NSD (mDNS) on the same network. No servers, accounts or internet.
* The source captures other apps' playback with `MediaProjection` + `AudioPlaybackCaptureConfiguration`.
* Each receiver explicitly accepts a session after comparing a 6-digit verification code, then plays
  through its normal Android audio output (incl. connected Bluetooth earbuds).

## Two modes

* **Wi-Fi network**: both phones on the same Wi-Fi/hotspot; discovery via mDNS.
* **Offline (Wi-Fi Direct)** *(new, untested)*: no router or internet needed. The sharing phone starts
  an offline hotspot (Wi-Fi Direct group); listeners choose Listen + Offline, tap Join and accept the
  Android invitation. Audio then uses the same encrypted TCP session. Bluetooth cannot carry this
  stream (see docs). Offline mode asks for the nearby-devices permission (location on Android 12 and older).

## Features

* Discover nearby NearAudio phones (start/stop on demand), duplicate-name safe list.
* Select up to 4 recipients; per-recipient state: Discovered, Selected, Connecting, Connected,
  Receiving audio, Disconnected, Failed.
* One failing recipient never affects the others (bounded per-recipient queues, stall watchdog).
* Foreground-service notification with Stop; Stop ends capture and playback on all phones.
* English and Dutch UI; Material 3, light/dark.

## Limitations

* Apps/content that block playback capture (DRM, `allowAudioPlaybackCapture=false`) produce silence.
* No video sync; latency roughly 150-400 ms plus headphone latency (estimate, not measured).
* Needs a Wi-Fi network without client isolation. Discovery/receiving only works while NearAudio
  is open (or a session is running).
* After the sender stops, recipients must reconnect and accept again for the next session.
* Android 10+ (API 29) only.

## Android versions

`minSdk 29` (playback capture API), `targetSdk`/`compileSdk 35`.

## Permissions

| Permission | Why |
|---|---|
| `INTERNET` | Local-network sockets only. No remote host is contacted. |
| `RECORD_AUDIO` | Required by `AudioRecord` for playback capture on the sharing phone. Microphone is never used. Requested only when you start sharing. |
| `FOREGROUND_SERVICE`, `..._MEDIA_PROJECTION`, `..._MEDIA_PLAYBACK` | Keep sharing/receiving alive with a visible notification. |
| `NEARBY_WIFI_DEVICES` (API 33+) / `ACCESS_FINE_LOCATION` (API <=32), `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE` | Offline mode (Wi-Fi Direct) only; requested when you start it. Location is never read. |
| `POST_NOTIFICATIONS` (API 33+) | Show that notification. Optional; asked when you start sharing. |

No Bluetooth, storage, contacts, accessibility or overlay permissions.

## Privacy

No analytics, ads, accounts or cloud. No audio is stored. Nothing persistent is written; the display
name and peer ids are random per launch and kept in memory only. Discovery/listening stops when you
leave the app (unless a session runs). The advertised name is display text, not identity.

## Build

Requires JDK 17+, Android SDK (platform 35). The Gradle wrapper **JAR is not included** (it could not
be generated offline). Generate it once with a local Gradle 8.10.2:

```
gradle wrapper --gradle-version 8.10.2
./gradlew testDebugUnitTest assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Install with `adb install -r <apk>` or copy it to the
phone and open it (allow installs from your file manager).

## GitHub Actions

`.github/workflows/android-ci.yml` (uses the SDK preinstalled on ubuntu-latest) runs unit tests and builds the debug APK on push/PR/manual run.
Download it from the run page -> Artifacts -> `nearaudio-debug-apk`. No secrets or signing keys needed.

## Testing with two phones

1. Install on both phones; connect both to the same Wi-Fi (or one phone's hotspot).
2. Open NearAudio on both; tap **Start discovery** on both.
3. On the sharing phone select the other phone, tap **Connect to selected**.
4. On the other phone, check the verification code matches, tap **Accept**.
5. On the sharing phone tap **Start sharing audio**, grant the microphone/notification prompts and the
   system capture consent. Play music/video in another app.
6. The receiver should play the audio (silence warning appears if nothing is captured).
7. Tap **Stop sharing** (or the notification's Stop): both phones stop.

## Verification status

| Item | Status |
|---|---|
| Source written, unit tests written | yes |
| Compiled / unit tests executed / APK built | **no** (no SDK, Gradle or network in authoring environment) |
| Emulator test | no |
| Real-device multi-phone audio test | no |

## License

Apache-2.0, see [LICENSE](LICENSE).
