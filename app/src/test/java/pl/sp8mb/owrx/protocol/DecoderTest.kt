package pl.sp8mb.owrx.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class DecoderTest {

    private fun fixture(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.readBytes()

    @Test
    fun `fft frames decode to fft_size dB values`() {
        val decoder = FftFrameDecoder().apply { compression = "adpcm" }
        for (n in 0..4) {
            val out = decoder.decode(fixture("fft_frame_$n.bin"))
            // captured from live server with fft_size=4092
            assertEquals(4092, out.size)
            // plausible dB range for a waterfall
            assertTrue("frame $n min ${out.min()}", out.min() > -200f)
            assertTrue("frame $n max ${out.max()}", out.max() < 50f)
            // most bins are noise floor below 0 dB
            val below = out.count { it < 0f }
            assertTrue("frame $n: $below/${out.size} below 0dB", below > out.size / 2)
        }
    }

    @Test
    fun `audio stream decodes to sane PCM`() {
        val stream = fixture("audio_stream.bin")
        val decoder = AudioStreamDecoder().apply { compression = "adpcm" }
        val pcm = decoder.decode(stream)

        // SYNC blocks present in capture
        val syncCount = countSyncWords(stream)
        assertTrue("no SYNC markers in fixture", syncCount > 0)

        // ~2 samples per data byte minus sync overhead
        assertTrue("decoded ${pcm.size} samples from ${stream.size} bytes", pcm.size > stream.size)

        // sane, non-constant audio
        var sumSq = 0.0
        for (s in pcm) sumSq += s.toDouble() * s
        val rms = sqrt(sumSq / pcm.size)
        assertTrue("rms $rms", rms > 1.0)
        val distinct = pcm.toSet().size
        assertTrue("only $distinct distinct sample values", distinct > 100)
    }

    @Test
    fun `streaming decode in chunks equals whole-buffer decode`() {
        val stream = fixture("audio_stream.bin")
        val whole = AudioStreamDecoder().apply { compression = "adpcm" }.decode(stream)

        val chunked = AudioStreamDecoder().apply { compression = "adpcm" }
        val out = ArrayList<Short>(whole.size)
        var pos = 0
        var chunkSize = 1
        while (pos < stream.size) {
            val end = minOf(pos + chunkSize, stream.size)
            out.addAll(chunked.decode(stream.copyOfRange(pos, end)).asList())
            pos = end
            chunkSize = (chunkSize * 3 + 7) % 1024 + 1 // deterministic pseudo-random sizes
        }
        assertEquals(whole.size, out.size)
        for (i in whole.indices) {
            assertEquals("sample $i", whole[i], out[i])
        }
    }

    private fun countSyncWords(data: ByteArray): Int {
        var count = 0
        for (i in 0..data.size - 4) {
            if (data[i] == 'S'.code.toByte() && data[i + 1] == 'Y'.code.toByte() &&
                data[i + 2] == 'N'.code.toByte() && data[i + 3] == 'C'.code.toByte()
            ) count++
        }
        return count
    }
}
