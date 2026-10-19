package app.nearaudio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays received PCM through the phone's normal media output (speaker, wired or Bluetooth headset;
 * routing is chosen by Android). Network thread -> bounded queue (drops oldest) -> player thread,
 * so network jitter never blocks reading and latency stays bounded (~200 ms queue + track buffer).
 */
class AudioPlayer(context: Context) : AudioSink {
    companion object {
        private const val QUEUE_FRAMES = 10
        private const val PREBUFFER_FRAMES = 3
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_FRAMES)

    @Volatile private var running = false

    @Volatile private var paused = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        // While another app holds focus we drop audio rather than talk over it.
        paused = change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    }

    override fun start() {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs)
            .setOnAudioFocusChangeListener(focusListener)
            .build()
        focusRequest = request
        audioManager.requestAudioFocus(request)

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(AudioConfig.SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val min = AudioTrack.getMinBufferSize(AudioConfig.SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(min, AudioConfig.FRAME_BYTES * 4))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        running = true
        thread = Thread({ loop(t) }, "near-audio-player").also { it.start() }
    }

    override fun play(pcm: ByteArray) {
        if (!queue.offer(pcm)) {
            queue.poll() // overrun: discard the oldest audio
            queue.offer(pcm)
        }
    }

    private fun loop(t: AudioTrack) {
        var written = 0
        var started = false
        try {
            while (running) {
                val frame = queue.poll(100, TimeUnit.MILLISECONDS) ?: continue
                if (paused) continue
                if (t.write(frame, 0, frame.size) < 0) break
                written++
                if (!started && written >= PREBUFFER_FRAMES) {
                    t.play() // small prebuffer to absorb jitter at start
                    started = true
                }
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IllegalStateException) {
            // track released while writing during shutdown
        }
    }

    override fun stop() {
        running = false
        thread?.join(300)
        thread = null
        queue.clear()
        track?.let {
            try {
                it.release()
            } catch (e: IllegalStateException) {
                // already released
            }
        }
        track = null
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }
}
