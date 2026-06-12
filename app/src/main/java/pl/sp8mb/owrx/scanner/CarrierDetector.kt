package pl.sp8mb.owrx.scanner

/**
 * Finds carriers in an FFT frame: bins above noiseFloor + thresholdDb,
 * grouped into contiguous runs.
 */
object CarrierDetector {

    data class Peak(
        val startBin: Int,
        val endBin: Int,
        val peakBin: Int,
        val peakDb: Float,
        val floorDb: Float,
    )

    fun noiseFloor(frame: FloatArray): Float {
        // median as a robust floor estimate
        val copy = frame.copyOf()
        copy.sort()
        return copy[copy.size / 2]
    }

    fun detect(frame: FloatArray, thresholdDb: Float, minBins: Int = 2): List<Peak> {
        if (frame.isEmpty()) return emptyList()
        val floor = noiseFloor(frame)
        val limit = floor + thresholdDb
        val peaks = ArrayList<Peak>()
        var start = -1
        var peakBin = -1
        var peakDb = -Float.MAX_VALUE
        for (i in frame.indices) {
            if (frame[i] > limit) {
                if (start < 0) {
                    start = i
                    peakBin = i
                    peakDb = frame[i]
                } else if (frame[i] > peakDb) {
                    peakBin = i
                    peakDb = frame[i]
                }
            } else if (start >= 0) {
                if (i - start >= minBins) peaks.add(Peak(start, i - 1, peakBin, peakDb, floor))
                start = -1
                peakDb = -Float.MAX_VALUE
            }
        }
        if (start >= 0 && frame.size - start >= minBins) {
            peaks.add(Peak(start, frame.size - 1, peakBin, peakDb, floor))
        }
        return peaks
    }

    /** Absolute frequency of a bin centre. */
    fun binToFreq(bin: Int, bins: Int, centerFreq: Long, sampRate: Int): Long =
        centerFreq - sampRate / 2 + ((bin + 0.5) / bins * sampRate).toLong()

    /** Bin index closest to an absolute frequency, or null if out of band. */
    fun freqToBin(freq: Long, bins: Int, centerFreq: Long, sampRate: Int): Int? {
        val frac = (freq - (centerFreq - sampRate / 2)).toDouble() / sampRate
        if (frac < 0 || frac >= 1) return null
        return (frac * bins).toInt()
    }

    fun snapToRaster(freq: Long, rasterHz: Int): Long =
        ((freq + rasterHz / 2) / rasterHz) * rasterHz
}
