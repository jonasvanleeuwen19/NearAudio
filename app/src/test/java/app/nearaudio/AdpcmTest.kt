package app.nearaudio

import app.nearaudio.audio.AdpcmTables
import app.nearaudio.audio.AudioConfig
import app.nearaudio.audio.AudioFormatId
import app.nearaudio.audio.FrameCodecs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class AdpcmTest {
    private fun sineFrame(startSample: Int, hz: Double): ByteArray {
        val bb = ByteBuffer.allocate(AudioConfig.FRAME_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until 960) {
            val v = (8000 * sin(2 * PI * hz * (startSample + i) / 48000.0)).toInt().toShort()
            bb.putShort(v).putShort(v)
        }
        return bb.array()
    }

    @Test fun tableHas89Steps() = assertEquals(89, AdpcmTables.STEP.size)

    @Test fun sizesMatchDocumentedBitrates() {
        val pcm = sineFrame(0, 440.0)
        assertEquals(488, FrameCodecs.encoder(AudioFormatId.ADPCM_24K_STEREO).encode(pcm).size)
        assertEquals(164, FrameCodecs.encoder(AudioFormatId.ADPCM_16K_MONO).encode(pcm).size)
        assertEquals(1920, AudioFormatId.ADPCM_24K_STEREO.frameBytes)
        assertEquals(640, AudioFormatId.ADPCM_16K_MONO.frameBytes)
    }

    @Test fun roundTripIsCloseToOriginal() {
        for (fmt in listOf(AudioFormatId.ADPCM_24K_STEREO, AudioFormatId.ADPCM_16K_MONO)) {
            val enc = FrameCodecs.encoder(fmt)
            var sig = 0.0
            var err = 0.0
            for (f in 0 until 20) {
                val pcm = sineFrame(f * 960, 440.0)
                val dec = FrameCodecs.decoder(fmt).decode(enc.encode(pcm)) // fresh decoder: frames are self-contained
                assertEquals(fmt.frameBytes, dec.size)
                if (f < 2) continue // encoder warm-up
                val d = ByteBuffer.wrap(dec).order(ByteOrder.LITTLE_ENDIAN)
                val o = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
                val n = dec.size / 2 / fmt.channels
                for (i in 0 until n) {
                    val ref = (0 until fmt.factor).sumOf { o.getShort((i * fmt.factor + it) * 4).toInt() } / fmt.factor
                    val got = d.getShort(i * fmt.channels * 2).toInt()
                    sig += ref.toDouble() * ref
                    err += (ref - got).toDouble() * (ref - got)
                }
            }
            assertTrue("SNR too low for $fmt", sqrt(err / sig) < 0.15)
        }
    }

    @Test fun malformedFramesRejected() {
        val dec = FrameCodecs.decoder(AudioFormatId.ADPCM_24K_STEREO)
        for (bad in listOf(ByteArray(0), ByteArray(3), ByteArray(9), ByteArray(5000))) {
            try { dec.decode(bad); fail("accepted ${bad.size} bytes") } catch (e: IOException) { /* expected */ }
        }
        val badIndex = ByteArray(488).also { it[2] = 200.toByte() }
        try { dec.decode(badIndex); fail() } catch (e: IOException) { /* expected */ }
    }

    @Test fun passthroughForPcm() {
        val pcm = sineFrame(0, 100.0)
        assertTrue(FrameCodecs.encoder(AudioFormatId.PCM_48K_STEREO).encode(pcm) === pcm)
    }
}
