package pl.sp8mb.owrx.protocol

/**
 * Decodes binary audio frames (WebSocket binary type 2/4) into 16-bit PCM.
 * ADPCM streams carry embedded SYNC state blocks; raw streams are int16 LE.
 */
class AudioStreamDecoder {

    private val codec = ImaAdpcmCodec()
    var compression: String = "none"

    fun reset() = codec.reset()

    fun decode(payload: ByteArray): ShortArray {
        return if (compression == "adpcm") {
            codec.decodeWithSync(payload)
        } else {
            val out = ShortArray(payload.size / 2)
            for (i in out.indices) {
                out[i] = ((payload[i * 2].toInt() and 0xFF) or (payload[i * 2 + 1].toInt() shl 8)).toShort()
            }
            out
        }
    }
}
