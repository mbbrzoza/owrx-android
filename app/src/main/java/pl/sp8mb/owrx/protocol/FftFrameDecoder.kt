package pl.sp8mb.owrx.protocol

/**
 * Decodes binary FFT waterfall frames (WebSocket binary type 1/3).
 * With fft_compression=="adpcm" the frame is IMA ADPCM (codec reset per frame)
 * and the first COMPRESS_FFT_PAD_N decoded samples are padding; values are dB*100.
 */
class FftFrameDecoder {

    private val codec = ImaAdpcmCodec()
    var compression: String = "none"

    fun decode(payload: ByteArray): FloatArray {
        return if (compression == "adpcm") {
            codec.reset()
            val i16 = codec.decode(payload)
            if (i16.size <= COMPRESS_FFT_PAD_N) return FloatArray(0)
            FloatArray(i16.size - COMPRESS_FFT_PAD_N) { i -> i16[i + COMPRESS_FFT_PAD_N] / 100f }
        } else {
            // raw little-endian float32
            val out = FloatArray(payload.size / 4)
            for (i in out.indices) {
                val bits = (payload[i * 4].toInt() and 0xFF) or
                        ((payload[i * 4 + 1].toInt() and 0xFF) shl 8) or
                        ((payload[i * 4 + 2].toInt() and 0xFF) shl 16) or
                        ((payload[i * 4 + 3].toInt() and 0xFF) shl 24)
                out[i] = Float.fromBits(bits)
            }
            out
        }
    }

    companion object {
        // must match COMPRESS_FFT_PAD_N in OpenWebRX csdr
        const val COMPRESS_FFT_PAD_N = 10
    }
}
