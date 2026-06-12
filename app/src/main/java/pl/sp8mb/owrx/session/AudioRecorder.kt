package pl.sp8mb.owrx.session

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records the received PCM stream to .m4a (AAC) files. Android has no built-in
 * MP3 encoder; AAC in an mp4 container plays everywhere and is smaller.
 * Files land in Android/data/pl.sp8mb.owrx/files/Music/.
 */
@Singleton
class AudioRecorder @Inject constructor(
    @ApplicationContext private val context: Context?,
) {
    data class State(
        val recording: Boolean = false,
        val startedAt: Long = 0,
        val filePath: String? = null,
        val lastFinishedFile: String? = null,
    )

    val state = MutableStateFlow(State())

    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var sampleRate = 0
    private var totalSamples = 0L
    private val bufferInfo = MediaCodec.BufferInfo()
    private val lock = Any()

    fun start(rate: Int, label: String) {
        synchronized(lock) {
            if (state.value.recording) return
            val dir = context?.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return
            dir.mkdirs()
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "OWRX_${stamp}_$label.m4a")

            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 48_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 64 * 1024)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
                configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            trackIndex = -1
            muxerStarted = false
            sampleRate = rate
            totalSamples = 0
            state.value = State(recording = true, startedAt = System.currentTimeMillis(), filePath = file.absolutePath)
        }
    }

    /** Called from the audio writer thread with each PCM chunk. */
    fun feed(pcm: ShortArray, rate: Int) {
        synchronized(lock) {
            val c = codec ?: return
            if (!state.value.recording) return
            if (rate != sampleRate) {
                // sample-rate switch (e.g. HD audio kicked in) — close the file
                stopLocked()
                return
            }
            val bytes = ByteBuffer.allocate(pcm.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in pcm) bytes.putShort(s)
            bytes.flip()

            while (bytes.hasRemaining()) {
                val inIdx = c.dequeueInputBuffer(10_000)
                if (inIdx < 0) break
                val inBuf = c.getInputBuffer(inIdx) ?: break
                inBuf.clear()
                val chunk = minOf(inBuf.remaining(), bytes.remaining())
                val slice = ByteArray(chunk)
                bytes.get(slice)
                inBuf.put(slice)
                val ptsUs = totalSamples * 1_000_000L / sampleRate
                totalSamples += chunk / 2
                c.queueInputBuffer(inIdx, 0, chunk, ptsUs, 0)
                drainLocked(false)
            }
        }
    }

    fun stop() {
        synchronized(lock) { stopLocked() }
    }

    private fun stopLocked() {
        val c = codec ?: return
        try {
            val inIdx = c.dequeueInputBuffer(10_000)
            if (inIdx >= 0) {
                c.queueInputBuffer(inIdx, 0, 0, totalSamples * 1_000_000L / sampleRate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            drainLocked(true)
        } catch (e: Exception) {
            android.util.Log.w("AudioRecorder", "stop", e)
        }
        try {
            c.stop()
            c.release()
        } catch (e: Exception) { /* already dead */ }
        try {
            if (muxerStarted) muxer?.stop()
            muxer?.release()
        } catch (e: Exception) { /* empty recording */ }
        codec = null
        muxer = null
        state.value = State(lastFinishedFile = state.value.filePath)
    }

    private fun drainLocked(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return
        while (true) {
            val outIdx = c.dequeueOutputBuffer(bufferInfo, if (endOfStream) 10_000 else 0)
            when {
                outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                outIdx >= 0 -> {
                    val outBuf = c.getOutputBuffer(outIdx)
                    if (outBuf != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        m.writeSampleData(trackIndex, outBuf, bufferInfo)
                    }
                    c.releaseOutputBuffer(outIdx, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> return
            }
        }
    }
}
