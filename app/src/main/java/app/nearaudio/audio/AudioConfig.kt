package app.nearaudio.audio

import java.io.IOException

/**
 * Wire audio format: 16-bit signed little-endian PCM, 48 kHz, stereo, 20 ms frames.
 * Bandwidth is ~1.5 Mbit/s per recipient (before ~0.4% crypto/framing overhead). PCM avoids a
 * codec dependency and encoder latency; Wi-Fi handles a handful of such streams easily.
 */
object AudioConfig {
    const val SAMPLE_RATE = 48_000
    const val CHANNELS = 2
    const val BYTES_PER_SAMPLE = 2
    const val FRAME_MS = 20
    const val FRAME_BYTES = SAMPLE_RATE * FRAME_MS / 1000 * CHANNELS * BYTES_PER_SAMPLE // 3840
}

/** Source of fixed-size PCM frames (implemented by playback capture; faked in tests). */
interface PcmSource {
    /** Blocks until [frame] is completely filled. Throws [IOException] on failure or after [abort]. */
    fun readFrame(frame: ByteArray)

    /** Thread-safe, idempotent: makes a blocked or future [readFrame] fail promptly. */
    fun abort()

    /** Releases all resources. Idempotent. */
    fun close()
}

/** Destination for received PCM frames (implemented by AudioTrack playback; faked in tests). */
interface AudioSink {
    fun start()

    /** Must not block for long; implementations drop old audio rather than grow unbounded. */
    fun play(pcm: ByteArray)

    fun stop()
}
