package app.nearaudio.audio

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.io.IOException
import java.nio.ByteBuffer

private const val AAC = MediaFormat.MIMETYPE_AUDIO_AAC

/** AudioSpecificConfig for AAC-LC, 48 kHz, stereo (fixed, so no config needs to be transmitted). */
private val ASC_48K_STEREO = byteArrayOf(0x11, 0x90.toByte())

/** Raw AAC-LC frames from the platform encoder (software or hardware; mandatory on Android). */
class AacEncoder(bitrate: Int) : FrameEncoder {
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val f = MediaFormat.createAudioFormat(AAC, 48_000, 2)
        f.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        f.setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
        f.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        codec = try {
            MediaCodec.createEncoderByType(AAC)
        } catch (e: Exception) {
            throw IOException("no AAC encoder: ${e.message}")
        }
        try {
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw IOException("AAC encoder failed: ${e.message}")
        }
    }

    override fun encode(pcm48: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>(2)
        try {
            var off = 0
            var stalls = 0
            while (off < pcm48.size) {
                val idx = codec.dequeueInputBuffer(10_000)
                if (idx < 0) {
                    drain(out)
                    if (++stalls > 20) throw IOException("AAC encoder stalled")
                    continue
                }
                val buf = codec.getInputBuffer(idx) ?: throw IOException("no input buffer")
                buf.clear()
                val n = minOf(buf.remaining(), pcm48.size - off)
                buf.put(pcm48, off, n)
                codec.queueInputBuffer(idx, 0, n, ptsUs, 0)
                ptsUs += n / 4 * 1_000_000L / 48_000
                off += n
            }
            drain(out)
        } catch (e: IllegalStateException) {
            throw IOException("AAC encoder error: ${e.message}")
        }
        return out
    }

    private fun drain(out: MutableList<ByteArray>) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, 0)
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (i < 0) continue // format / buffer set changed
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val b = ByteArray(info.size)
                val ob = codec.getOutputBuffer(i)!!
                ob.position(info.offset)
                ob.get(b)
                out.add(b)
            }
            codec.releaseOutputBuffer(i, false)
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}

class AacDecoder : FrameDecoder {
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()
    private var ptsUs = 0L

    init {
        val f = MediaFormat.createAudioFormat(AAC, 48_000, 2)
        f.setByteBuffer("csd-0", ByteBuffer.wrap(ASC_48K_STEREO))
        f.setInteger(MediaFormat.KEY_IS_ADTS, 0)
        codec = try {
            MediaCodec.createDecoderByType(AAC)
        } catch (e: Exception) {
            throw IOException("no AAC decoder: ${e.message}")
        }
        try {
            codec.configure(f, null, null, 0)
            codec.start()
        } catch (e: Exception) {
            codec.release()
            throw IOException("AAC decoder failed: ${e.message}")
        }
    }

    override fun decode(data: ByteArray): List<ByteArray> {
        if (data.isEmpty() || data.size > 4096) throw IOException("bad AAC frame size ${data.size}")
        val out = ArrayList<ByteArray>(2)
        try {
            var idx = codec.dequeueInputBuffer(10_000)
            var tries = 0
            while (idx < 0) {
                drain(out, 0)
                if (++tries > 20) throw IOException("AAC decoder stalled")
                idx = codec.dequeueInputBuffer(10_000)
            }
            val buf = codec.getInputBuffer(idx) ?: throw IOException("no input buffer")
            buf.clear()
            buf.put(data)
            codec.queueInputBuffer(idx, 0, data.size, ptsUs, 0)
            ptsUs += 21_333
            drain(out, 5_000) // wait briefly so output is not always one frame late
        } catch (e: IllegalStateException) {
            throw IOException("AAC decoder error: ${e.message}")
        }
        return out
    }

    private fun drain(out: MutableList<ByteArray>, firstTimeoutUs: Long) {
        var timeout = firstTimeoutUs
        while (true) {
            val i = codec.dequeueOutputBuffer(info, timeout)
            timeout = 0
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) return
            if (i < 0) continue
            if (info.size > 0) {
                val b = ByteArray(info.size)
                val ob = codec.getOutputBuffer(i)!!
                ob.position(info.offset)
                ob.get(b)
                out.add(b)
            }
            codec.releaseOutputBuffer(i, false)
        }
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
    }
}
