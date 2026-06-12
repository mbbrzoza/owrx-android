package pl.sp8mb.owrx.session

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import java.util.concurrent.ArrayBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PCM16 playback: jitter queue + dedicated writer thread feeding AudioTrack.
 * Standard stream is 12 kHz mono; HD frames switch the track to 48 kHz.
 */
@Singleton
class AudioPipeline @Inject constructor() {

    private data class Chunk(val pcm: ShortArray, val sampleRate: Int)

    private val queue = ArrayBlockingQueue<Chunk>(QUEUE_CAPACITY)

    @Volatile
    private var running = false
    private var thread: Thread? = null

    @Volatile
    var muted = false

    fun start() {
        if (running) return
        running = true
        thread = Thread(::writerLoop, "owrx-audio").apply {
            priority = Thread.MAX_PRIORITY - 1
            start()
        }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
        queue.clear()
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
                if (muted) continue
                track.write(chunk.pcm, 0, chunk.pcm.size)
            }
        } finally {
            track?.release()
        }
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
