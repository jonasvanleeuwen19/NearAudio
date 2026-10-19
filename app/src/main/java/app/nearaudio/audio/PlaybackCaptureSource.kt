package app.nearaudio.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import java.io.IOException

/**
 * Captures other apps' playback via MediaProjection (not the microphone). Apps/content that opt out
 * of capture (allowAudioPlaybackCapture=false, DRM, some usages) yield silence or fail; Android decides.
 * The RECORD_AUDIO permission is required by AudioRecord even for playback capture.
 */
class PlaybackCaptureSource(private val projection: MediaProjection) : PcmSource {
    private val record: AudioRecord

    @Volatile private var aborted = false
    private var closed = false

    init {
        val config = AudioPlaybackCaptureConfiguration.Builder(projection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioConfig.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()
        val min = AudioRecord.getMinBufferSize(AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord.Builder()
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, AudioConfig.FRAME_BYTES * 8))
            .setAudioPlaybackCaptureConfig(config)
            .build()
        record = rec
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("playback capture could not be initialised")
        }
        rec.startRecording()
        if (rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            rec.release()
            throw IOException("playback capture did not start recording")
        }
    }

    override fun readFrame(frame: ByteArray) {
        var off = 0
        while (off < frame.size) {
            if (aborted) throw IOException("capture stopped")
            val n = record.read(frame, off, frame.size - off)
            if (n < 0) throw IOException("capture read failed ($n)")
            off += n
        }
    }

    override fun abort() {
        aborted = true
        try {
            record.stop() // unblocks a read in progress
        } catch (e: IllegalStateException) {
            // already stopped/released
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        abort()
        record.release()
        projection.stop()
    }
}
