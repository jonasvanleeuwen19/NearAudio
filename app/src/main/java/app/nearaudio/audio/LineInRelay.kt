package app.nearaudio.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process

/**
 * Wired "audio jack / USB audio" listening: records the wired input (3.5 mm headset jack mic line or a
 * USB audio adapter input) and plays it through the phone's normal output, preferably Bluetooth
 * earbuds or the speaker, never back into the cable. Analog input is mono/low level on most phones.
 */
@SuppressLint("MissingPermission") // RECORD_AUDIO is requested by the activity first
class LineInRelay(private val ctx: Context, private val targetFrames: Int) {
    companion object {
        private val INPUT_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY,
        )
        private const val TYPE_BLE_HEADSET = 26 // API 31 constant, avoids a version check
    }

    private val am = ctx.getSystemService(AudioManager::class.java)
    private var record: AudioRecord? = null
    private var player: AudioPlayer? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    /** Returns null on success or a short error code. */
    fun start(): String? {
        val input = am.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type in INPUT_TYPES } ?: return "no-input"
        var fmt = AudioFormatId.PCM_48K_STEREO
        var rec: AudioRecord? = null
        for ((mask, f) in listOf(
            AudioFormat.CHANNEL_IN_STEREO to AudioFormatId.PCM_48K_STEREO,
            AudioFormat.CHANNEL_IN_MONO to AudioFormatId.PCM_48K_MONO,
        )) {
            rec = build(mask, f) ?: continue
            fmt = f
            break
        }
        if (rec == null) return "record-init"
        rec.setPreferredDevice(input)
        val out = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).let { devs ->
            devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == TYPE_BLE_HEADSET }
                ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        }
        val p = AudioPlayer(ctx, fmt, targetFrames, out)
        try {
            p.start()
            rec.startRecording()
        } catch (e: Exception) {
            rec.release()
            p.stop()
            return "start-failed"
        }
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            p.stop()
            return "start-failed"
        }
        record = rec
        player = p
        running = true
        val bytes = fmt.frameBytes
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            while (running) {
                val frame = ByteArray(bytes)
                var off = 0
                while (running && off < bytes) {
                    val n = rec.read(frame, off, bytes - off)
                    if (n < 0) {
                        running = false
                        break
                    }
                    off += n
                }
                if (running) p.play(frame)
            }
        }, "near-audio-linein").also { it.start() }
        return null
    }

    private fun build(mask: Int, f: AudioFormatId): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(f.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        for (source in listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) { // no AGC/noise suppression
            try {
                val r = AudioRecord.Builder()
                    .setAudioSource(source)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(f.sampleRate).setChannelMask(mask).build(),
                    )
                    .setBufferSizeInBytes(maxOf(min, f.frameBytes * 8))
                    .build()
                if (r.state == AudioRecord.STATE_INITIALIZED) return r
                r.release()
            } catch (e: Exception) {
                // try next source
            }
        }
        return null
    }

    fun stop() {
        running = false
        runCatching { record?.stop() }
        thread?.join(500)
        thread = null
        runCatching { record?.release() }
        record = null
        player?.stop()
        player = null
    }
}
