package pl.sp8mb.owrx.session

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PCM16 playback: jitter queue + dedicated writer thread feeding AudioTrack.
 * Standard stream is 12 kHz mono; HD frames switch the track to 48 kHz.
 */
@Singleton
class AudioPipeline @Inject constructor(
    @ApplicationContext private val context: Context?,
    private val recorder: AudioRecorder?,
) {
    private var focusRequest: AudioFocusRequest? = null

    /** User-controlled mute (UI toggle). */
    val userMuted = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Software volume gain (0..2, 1 = unity); applied to PCM with saturation. */
    val volume = kotlinx.coroutines.flow.MutableStateFlow(1.0f)

    @Volatile
    private var focusMuted = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        focusMuted = change == AudioManager.AUDIOFOCUS_LOSS ||
            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
    }

    private data class Chunk(val pcm: ShortArray, val sampleRate: Int)

    private val queue = ArrayBlockingQueue<Chunk>(QUEUE_CAPACITY)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    private val muted: Boolean get() = userMuted.value || focusMuted

    fun start() {
        if (running) return
        running = true
        requestFocus()
        thread = Thread(::writerLoop, "owrx-audio").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running = false
        abandonFocus()
        thread?.interrupt()
        thread = null
        queue.clear()
    }

    private fun requestFocus() {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setOnAudioFocusChangeListener(focusListener)
                .build()
                .also { am.requestAudioFocus(it) }
        } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
    }

    private fun abandonFocus() {
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            am.abandonAudioFocus(focusListener)
        }
    }

    fun submit(pcm: ShortArray, hd: Boolean) {
        if (!running || pcm.isEmpty()) return
        val chunk = Chunk(pcm, if (hd) HD_RATE else BASE_RATE)
        // drop-oldest when the LTE link bursts after a stall
        while (!queue.offer(chunk)) {
            queue.poll()
        }
    }

    private fun writerLoop() {
        var track: AudioTrack? = null
        var trackRate = 0
        try {
            while (running) {
                val chunk = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    break
                }
                if (track == null || trackRate != chunk.sampleRate) {
                    track?.release()
                    track = createTrack(chunk.sampleRate)
                    trackRate = chunk.sampleRate
                    track.play()
                }
                recorder?.feed(chunk.pcm, chunk.sampleRate)
                if (muted) continue
                val g = volume.value
                val out = if (g == 1.0f) chunk.pcm else applyGain(chunk.pcm, g)
                track.write(out, 0, out.size)
            }
        } finally {
            track?.release()
        }
    }

    private fun applyGain(pcm: ShortArray, gain: Float): ShortArray {
        val out = ShortArray(pcm.size)
        for (i in pcm.indices) {
            val v = (pcm[i] * gain).toInt()
            out[i] = if (v > 32767) 32767 else if (v < -32768) -32768 else v.toShort()
        }
        return out
    }

    private fun createTrack(sampleRate: Int): AudioTrack {
        val minBuf = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBuf * 4, sampleRate /* 0.5 s of int16 */)
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_POWER_SAVING)
            .build()
    }

    companion object {
        const val BASE_RATE = 12000
        const val HD_RATE = 48000
        private const val QUEUE_CAPACITY = 64
    }
}
