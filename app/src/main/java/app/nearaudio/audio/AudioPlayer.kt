package app.nearaudio.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Process
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Plays received PCM through the phone's normal media output (speaker, wired or Bluetooth headset).
 *
 * Adaptive jitter buffer, measured in 20 ms frames:
 *  - playback only starts once [targetFrames] are queued, so network bursts/gaps are absorbed;
 *  - on underrun the track is paused and refilled to the target instead of stuttering;
 *  - if the queue grows past the high-water mark (burst after a stall) the oldest audio is skipped,
 *    so delay snaps back to ~target instead of staying high.
 */
class AudioPlayer(context: Context, private val format: AudioFormatId, private val targetFrames: Int) : AudioSink {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val highWater = targetFrames * 2 + 2
    private val queue = ArrayBlockingQueue<ByteArray>(targetFrames * 3 + 6)

    @Volatile private var running = false

    @Volatile private var paused = false
    private var thread: Thread? = null
    private var track: AudioTrack? = null
    private var focusRequest: AudioFocusRequest? = null

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
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

        val mask = if (format.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val af = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(format.sampleRate)
            .setChannelMask(mask)
            .build()
        val min = AudioTrack.getMinBufferSize(format.sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(attrs)
            .setAudioFormat(af)
            .setBufferSizeInBytes(maxOf(min, format.frameBytes * 6))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track = t
        running = true
        thread = Thread({ loop(t) }, "near-audio-player").also { it.start() }
    }

    override fun play(pcm: ByteArray) {
        if (!queue.offer(pcm)) {
            queue.poll()
            queue.offer(pcm)
        }
    }

    private fun loop(t: AudioTrack) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        var buffering = true
        try {
            while (running) {
                if (buffering) {
                    if (queue.size >= targetFrames) {
                        t.play()
                        buffering = false
                    } else {
                        Thread.sleep(4)
                        continue
                    }
                }
                var frame = queue.poll(80, TimeUnit.MILLISECONDS)
                if (frame == null) {
                    t.pause() // underrun: rebuffer rather than play glitchy fragments
                    buffering = true
                    continue
                }
                while (queue.size > highWater) frame = queue.poll() ?: break // catch up after a burst
                if (paused) continue
                if (t.write(frame, 0, frame.size) < 0) break
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: IllegalStateException) {
            // track released during shutdown
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
