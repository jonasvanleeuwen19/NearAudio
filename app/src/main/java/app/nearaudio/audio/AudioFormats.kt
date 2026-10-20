package app.nearaudio.audio

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire audio formats. The capture side always produces 48 kHz stereo PCM; slower links (Bluetooth)
 * downsample and compress with IMA ADPCM (4 bits/sample, no codec latency, each frame carries its
 * own predictor state so a dropped frame cannot corrupt later ones).
 */
enum class AudioFormatId(val id: Int, val sampleRate: Int, val channels: Int, val factor: Int, val adpcm: Boolean) {
    PCM_48K_STEREO(0, 48_000, 2, 1, false), // ~1.5 Mbit/s
    ADPCM_24K_STEREO(1, 24_000, 2, 2, true), // ~195 kbit/s
    ADPCM_16K_MONO(2, 16_000, 1, 3, true); // ~66 kbit/s

    /** Bytes of decoded PCM per 20 ms frame. */
    val frameBytes: Int get() = sampleRate * AudioConfig.FRAME_MS / 1000 * channels * 2

    companion object {
        fun fromId(id: Int): AudioFormatId? = entries.firstOrNull { it.id == id }
    }
}

fun interface FrameEncoder {
    /** [pcm48] is one 48 kHz stereo 16-bit LE frame. */
    fun encode(pcm48: ByteArray): ByteArray
}

fun interface FrameDecoder {
    /** Returns PCM in the format's native rate/channels. Throws [IOException] on malformed data. */
    fun decode(data: ByteArray): ByteArray
}

object FrameCodecs {
    fun encoder(f: AudioFormatId): FrameEncoder = if (f.adpcm) AdpcmEncoder(f) else FrameEncoder { it }
    fun decoder(f: AudioFormatId): FrameDecoder = if (f.adpcm) AdpcmDecoder(f) else FrameDecoder { it }
}

object AdpcmTables {
    val STEP = intArrayOf(
        7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107,
        118, 130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963,
        1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894,
        6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
    )
    val INDEX = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8)
    val MAX_INDEX = STEP.size - 1
}

class AdpcmState(var pred: Int = 0, var index: Int = 0) {
    private fun advance(code: Int, vpdiff: Int) {
        pred = (if (code and 8 != 0) pred - vpdiff else pred + vpdiff).coerceIn(-32768, 32767)
        index = (index + AdpcmTables.INDEX[code and 7]).coerceIn(0, AdpcmTables.MAX_INDEX)
    }

    fun encode(sample: Int): Int {
        var diff = sample - pred
        val sign = if (diff < 0) 8 else 0
        if (diff < 0) diff = -diff
        var step = AdpcmTables.STEP[index]
        var code = 0
        var vpdiff = step shr 3
        if (diff >= step) { code = code or 4; diff -= step; vpdiff += step }
        step = step shr 1
        if (diff >= step) { code = code or 2; diff -= step; vpdiff += step }
        step = step shr 1
        if (diff >= step) { code = code or 1; vpdiff += step }
        code = code or sign
        advance(code, vpdiff)
        return code
    }

    fun decode(code: Int): Int {
        val step = AdpcmTables.STEP[index]
        var vpdiff = step shr 3
        if (code and 4 != 0) vpdiff += step
        if (code and 2 != 0) vpdiff += step shr 1
        if (code and 1 != 0) vpdiff += step shr 2
        advance(code, vpdiff)
        return pred
    }
}

/** Layout per channel: [pred:int16 LE][index:1][0:1][nibbles, low nibble first]; channels back to back. */
class AdpcmEncoder(private val fmt: AudioFormatId) : FrameEncoder {
    private val states = Array(fmt.channels) { AdpcmState() }

    override fun encode(pcm48: ByteArray): ByteArray {
        val bb = ByteBuffer.wrap(pcm48).order(ByteOrder.LITTLE_ENDIAN)
        val outFrames = pcm48.size / 4 / fmt.factor
        val ch = Array(fmt.channels) { IntArray(outFrames) }
        for (i in 0 until outFrames) {
            var l = 0
            var r = 0
            for (k in 0 until fmt.factor) { // box filter = crude low-pass before decimation
                val p = (i * fmt.factor + k) * 4
                l += bb.getShort(p)
                r += bb.getShort(p + 2)
            }
            l /= fmt.factor
            r /= fmt.factor
            if (fmt.channels == 1) ch[0][i] = (l + r) / 2 else { ch[0][i] = l; ch[1][i] = r }
        }
        val perCh = 4 + (outFrames + 1) / 2
        val out = ByteArray(perCh * fmt.channels)
        for (c in 0 until fmt.channels) {
            val st = states[c]
            val base = c * perCh
            out[base] = (st.pred and 0xFF).toByte()
            out[base + 1] = ((st.pred shr 8) and 0xFF).toByte()
            out[base + 2] = st.index.toByte()
            var i = 0
            var p = base + 4
            while (i < outFrames) {
                val lo = st.encode(ch[c][i])
                val hi = if (i + 1 < outFrames) st.encode(ch[c][i + 1]) else 0
                out[p++] = (lo or (hi shl 4)).toByte()
                i += 2
            }
        }
        return out
    }
}

class AdpcmDecoder(private val fmt: AudioFormatId) : FrameDecoder {
    override fun decode(data: ByteArray): ByteArray {
        val chs = fmt.channels
        if (data.size % chs != 0 || data.size > fmt.frameBytes) throw IOException("bad ADPCM frame size ${data.size}")
        val perCh = data.size / chs
        if (perCh < 5) throw IOException("ADPCM frame too short")
        val n = (perCh - 4) * 2
        val samples = Array(chs) { IntArray(n) }
        for (c in 0 until chs) {
            val base = c * perCh
            val pred = (data[base + 1].toInt() shl 8) or (data[base].toInt() and 0xFF)
            val index = data[base + 2].toInt() and 0xFF
            if (index > AdpcmTables.MAX_INDEX) throw IOException("bad ADPCM index")
            val st = AdpcmState(pred, index)
            var i = 0
            while (i < n) {
                val b = data[base + 4 + i / 2].toInt() and 0xFF
                samples[c][i] = st.decode(b and 0xF)
                samples[c][i + 1] = st.decode(b shr 4)
                i += 2
            }
        }
        val out = ByteBuffer.allocate(n * chs * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until n) for (c in 0 until chs) out.putShort(samples[c][i].toShort())
        return out.array()
    }
}
