package pl.sp8mb.owrx.ui.receiver

import android.graphics.Color

/**
 * 256-entry waterfall colour LUT — the classic OpenWebRX "teejeez" scheme
 * (black → blue → cyan → green → yellow → red → white).
 */
object ColorMap {

    private val anchors = intArrayOf(
        0x000000, 0x0000FF, 0x00FFFF, 0x00FF00, 0xFFFF00, 0xFF0000, 0xFF00FF, 0xFFFFFF,
    )

    val lut: IntArray = IntArray(256) { i ->
        val pos = i / 255f * (anchors.size - 1)
        val idx = pos.toInt().coerceAtMost(anchors.size - 2)
        val frac = pos - idx
        val c0 = anchors[idx]
        val c1 = anchors[idx + 1]
        val r = ((c0 shr 16 and 0xFF) * (1 - frac) + (c1 shr 16 and 0xFF) * frac).toInt()
        val g = ((c0 shr 8 and 0xFF) * (1 - frac) + (c1 shr 8 and 0xFF) * frac).toInt()
        val b = ((c0 and 0xFF) * (1 - frac) + (c1 and 0xFF) * frac).toInt()
        Color.rgb(r, g, b)
    }

    /** Map dB value to colour given display range. */
    fun color(db: Float, minDb: Float, maxDb: Float): Int {
        val t = ((db - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
        return lut[(t * 255).toInt()]
    }
}
