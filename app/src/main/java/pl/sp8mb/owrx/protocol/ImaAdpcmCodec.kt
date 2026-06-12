package pl.sp8mb.owrx.protocol

/**
 * IMA ADPCM decoder — port 1:1 of ImaAdpcmCodec from OpenWebRX+ htdocs/lib/AudioEngine.js.
 * The OWRX flavour embeds codec state in the stream: a literal "SYNC" word followed by
 * 4 bytes (int16 LE stepIndex, int16 LE predictor) roughly every 1000 data bytes.
 */
class ImaAdpcmCodec {

    private var stepIndex = 0
    private var predictor = 0
    private var step = 0

    // decodeWithSync state machine
    private var synchronized = 0
    private var syncCounter = 0
    private var phase = 0
    private val syncBuffer = ByteArray(4)
    private var syncBufferIndex = 0

    fun reset() {
        stepIndex = 0
        predictor = 0
        step = 0
        synchronized = 0
        syncCounter = 0
        phase = 0
        syncBufferIndex = 0
    }

    /** Plain decode, no sync words (used for FFT frames; caller resets per frame). */
    fun decode(data: ByteArray): ShortArray {
        val output = ShortArray(data.size * 2)
        for (i in data.indices) {
            val b = data[i].toInt() and 0xFF
            output[i * 2] = decodeNibble(b and 0x0F)
            output[i * 2 + 1] = decodeNibble((b shr 4) and 0x0F)
        }
        return output
    }

    /**
     * Streaming decode with embedded SYNC blocks. Keeps state across calls,
     * so consecutive WebSocket audio frames can be fed directly.
     */
    fun decodeWithSync(data: ByteArray): ShortArray {
        val output = ShortArray(data.size * 2)
        var oi = 0
        for (index in data.indices) {
            val b = data[index].toInt() and 0xFF
            when (phase) {
                0 -> {
                    // search for sync word
                    if (b != SYNC_WORD[synchronized++].code) {
                        synchronized = 0
                    }
                    if (synchronized == 4) {
                        syncBufferIndex = 0
                        phase = 1
                    }
                }
                1 -> {
                    // read codec runtime data from stream
                    syncBuffer[syncBufferIndex++] = data[index]
                    if (syncBufferIndex == 4) {
                        stepIndex = ((syncBuffer[0].toInt() and 0xFF) or (syncBuffer[1].toInt() shl 8)).toShort().toInt()
                        predictor = ((syncBuffer[2].toInt() and 0xFF) or (syncBuffer[3].toInt() shl 8)).toShort().toInt()
                        syncCounter = 1000
                        phase = 2
                    }
                }
                2 -> {
                    output[oi++] = decodeNibble(b and 0x0F)
                    output[oi++] = decodeNibble(b shr 4)
                    if (syncCounter-- == 0) {
                        synchronized = 0
                        phase = 0
                    }
                }
            }
        }
        return output.copyOf(oi)
    }

    private fun decodeNibble(nibble: Int): Short {
        stepIndex += IMA_INDEX_TABLE[nibble]
        stepIndex = stepIndex.coerceIn(0, 88)

        var diff = step shr 3
        if (nibble and 1 != 0) diff += step shr 2
        if (nibble and 2 != 0) diff += step shr 1
        if (nibble and 4 != 0) diff += step
        if (nibble and 8 != 0) diff = -diff

        predictor += diff
        predictor = predictor.coerceIn(-32768, 32767)

        step = IMA_STEP_TABLE[stepIndex]

        return predictor.toShort()
    }

    companion object {
        private const val SYNC_WORD = "SYNC"

        private val IMA_INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

        private val IMA_STEP_TABLE = intArrayOf(
            7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
            19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
            50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
            130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
            337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
            876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
            2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
            5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
            15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
        )
    }
}
