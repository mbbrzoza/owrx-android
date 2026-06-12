package pl.sp8mb.owrx.ui.receiver

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View

/**
 * Spectrum + waterfall display fed with FFT frames.
 * Renders into a ring-buffer bitmap (one row per frame), with pinch-zoom and
 * pan as pure view transforms. Tap tunes (callback gets absolute Hz).
 */
class WaterfallView(context: Context) : View(context) {

    /** Set from the radio config. */
    var centerFreq: Long = 0
    var sampRate: Int = 0

    /** Current passband for the overlay (absolute Hz). */
    var tunedFreq: Long = 0
        set(value) {
            field = value
            invalidate()
        }
    var passbandLow: Int = -6000
    var passbandHigh: Int = 6000

    var onTune: ((Long) -> Unit)? = null

    /** Bookmarks shown as tags on the frequency scale band. */
    data class Tag(val freq: Long, val name: String, val modulation: String?)

    var tags: List<Tag> = emptyList()
        set(value) {
            field = value
            invalidate()
        }
    var onTagTap: ((Tag) -> Unit)? = null

    // display range, auto-adjusted from frame percentiles
    private var minDb = -100f
    private var maxDb = -20f
    private var autoLevels = true

    // zoom/pan: fraction of band shown [viewStart, viewEnd] in 0..1
    private var viewStart = 0f
    private var viewEnd = 1f

    private var bitmap: Bitmap? = null
    private var bmWidth = 0
    private var rowPointer = 0
    private var rowsFilled = 0
    private var rowBuf: IntArray = IntArray(0)
    private var lastFrame: FloatArray = FloatArray(0)

    private val spectrumPaint = Paint().apply {
        color = 0xFFB0E0FF.toInt()
        strokeWidth = 2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val passbandPaint = Paint().apply { color = 0x3FFFFFFF }
    private val tunePaint = Paint().apply {
        color = 0xFFFF4040.toInt()
        strokeWidth = 3f
    }
    private val bmPaint = Paint().apply { isFilterBitmap = false }
    private val srcRect = Rect()
    private val dstRect = Rect()

    private val density = resources.displayMetrics.density
    private val scaleBandH: Float get() = 52f * density
    private val scaleBgPaint = Paint().apply { color = 0xFF11151A.toInt() }
    private val tickPaint = Paint().apply {
        color = 0xFF8090A0.toInt()
        strokeWidth = 1.5f * density
    }
    private val scaleTextPaint = Paint().apply {
        color = 0xFFC0D0E0.toInt()
        textSize = 10f * density
        isAntiAlias = true
        typeface = android.graphics.Typeface.MONOSPACE
    }
    private val tagTextPaint = Paint().apply {
        color = 0xFF101418.toInt()
        textSize = 9.5f * density
        isAntiAlias = true
    }
    private val tagBgPaint = Paint().apply {
        color = 0xFFE8B440.toInt()
        isAntiAlias = true
    }
    /** screen rects of drawn tags for tap hit-testing */
    private val tagHits = ArrayList<Pair<android.graphics.RectF, Tag>>()

    fun addFrame(frame: FloatArray) {
        if (frame.isEmpty()) return
        lastFrame = frame
        ensureBitmap()
        val bm = bitmap ?: return

        if (autoLevels) updateLevels(frame)

        // decimate frame to bitmap width with max()
        val w = bmWidth
        val binsPerPx = frame.size.toFloat() / w
        for (x in 0 until w) {
            val from = (x * binsPerPx).toInt()
            val to = ((x + 1) * binsPerPx).toInt().coerceAtMost(frame.size).coerceAtLeast(from + 1)
            var v = frame[from]
            for (i in from + 1 until to) if (frame[i] > v) v = frame[i]
            rowBuf[x] = ColorMap.color(v, minDb, maxDb)
        }
        bm.setPixels(rowBuf, 0, w, 0, rowPointer, w, 1)
        rowPointer = (rowPointer + 1) % WATERFALL_ROWS
        if (rowsFilled < WATERFALL_ROWS) rowsFilled++
        postInvalidateOnAnimation()
    }

    private var sortBuf = FloatArray(0)

    /**
     * Percentile-based palette targets, like the web auto-adjust: floor from
     * the 15th percentile, ceiling from the 98th — a single strong carrier
     * no longer stretches (washes out) the whole palette.
     */
    private fun levelTargets(frame: FloatArray): Pair<Float, Float> {
        if (sortBuf.size != frame.size) sortBuf = FloatArray(frame.size)
        frame.copyInto(sortBuf)
        sortBuf.sort()
        val p15 = sortBuf[(sortBuf.size * 15) / 100]
        val p98 = sortBuf[(sortBuf.size * 98) / 100]
        var min = p15 - 3f
        var max = p98 + 12f
        if (max - min < 20f) max = min + 20f
        return min to max
    }

    /** Instantly re-fit the colour range to the latest frame. */
    fun snapLevels() {
        val frame = lastFrame
        if (frame.isEmpty()) return
        val (min, max) = levelTargets(frame)
        minDb = min
        maxDb = max
        invalidate()
    }

    private fun updateLevels(frame: FloatArray) {
        val (targetMin, targetMax) = levelTargets(frame)
        // asymmetric follow: expand range fast, contract very slowly —
        // symmetric per-frame tracking makes row brightness pump (striped waterfall)
        minDb += (targetMin - minDb) * if (targetMin < minDb) 0.5f else 0.01f
        maxDb += (targetMax - maxDb) * if (targetMax > maxDb) 0.5f else 0.01f
        if (maxDb - minDb < 20f) maxDb = minDb + 20f
    }

    private fun ensureBitmap() {
        val targetW = lastFrame.size.coerceAtMost(MAX_BITMAP_WIDTH).coerceAtLeast(256)
        if (bitmap == null || bmWidth != targetW) {
            bmWidth = targetW
            rowBuf = IntArray(targetW)
            bitmap = Bitmap.createBitmap(targetW, WATERFALL_ROWS, Bitmap.Config.ARGB_8888)
            rowPointer = 0
            rowsFilled = 0
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width
        val h = height
        if (w == 0 || h == 0) return
        val scaleH = scaleBandH.toInt()
        val spectrumH = ((h - scaleH) * SPECTRUM_FRACTION).toInt()
        val waterfallH = h - scaleH - spectrumH

        drawScale(canvas, w, scaleH)
        canvas.save()
        canvas.translate(0f, scaleH.toFloat())
        drawSpectrum(canvas, w, spectrumH)
        drawWaterfall(canvas, w, spectrumH, waterfallH)
        canvas.restore()
        drawOverlay(canvas, w, h, scaleH)
    }

    private fun drawScale(canvas: Canvas, w: Int, scaleH: Int) {
        canvas.drawRect(0f, 0f, w.toFloat(), scaleH.toFloat(), scaleBgPaint)
        tagHits.clear()
        if (centerFreq == 0L || sampRate == 0) return

        val bandStart = centerFreq - sampRate / 2
        val visStart = bandStart + (viewStart * sampRate).toLong()
        val visEnd = bandStart + (viewEnd * sampRate).toLong()
        val span = (visEnd - visStart).coerceAtLeast(1)

        // pick a tick step giving ~5-8 labels
        val steps = longArrayOf(
            1_000, 2_500, 5_000, 10_000, 25_000, 50_000,
            100_000, 250_000, 500_000, 1_000_000, 2_500_000, 5_000_000,
        )
        val step = steps.firstOrNull { span / it <= 8 } ?: 10_000_000L

        val tickTop = scaleH - 8f * density
        val labelY = scaleH - 12f * density
        var f = (visStart / step) * step
        while (f <= visEnd) {
            if (f >= visStart) {
                val x = freqToXAbs(f, w)
                canvas.drawLine(x, tickTop, x, scaleH.toFloat(), tickPaint)
                val label = "%.3f".format(f / 1e6)
                val tw = scaleTextPaint.measureText(label)
                val tx = (x - tw / 2).coerceIn(0f, w - tw)
                canvas.drawText(label, tx, labelY, scaleTextPaint)
            }
            f += step
        }
        canvas.drawLine(0f, scaleH.toFloat(), w.toFloat(), scaleH.toFloat(), tickPaint)

        // bookmark tags, centred on their true frequency, staggered into two
        // rows so neighbouring tags stay visible; only a double overlap is
        // reduced to a marker (zoom in to reveal it)
        val tagH = 13f * density
        val pad = 3f * density
        val rowY = floatArrayOf(1f * density, (2f + 14.5f) * density)
        val lastEnd = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE)
        for (tag in tags.sortedBy { it.freq }) {
            if (tag.freq < visStart || tag.freq > visEnd) continue
            val x = freqToXAbs(tag.freq, w)
            val name = if (tag.name.length > 12) tag.name.take(11) + "…" else tag.name
            val tw = tagTextPaint.measureText(name)
            val left = x - tw / 2 - pad
            val right = left + tw + 2 * pad
            val row = when {
                left >= lastEnd[0] + 2f -> 0
                left >= lastEnd[1] + 2f -> 1
                else -> -1
            }
            if (row < 0 || left < 0f || right > w) {
                // no room in either row — still mark the exact spot
                canvas.drawLine(x, scaleH - 14f * density, x, scaleH.toFloat(), tagBgPaint)
                continue
            }
            val top = rowY[row]
            canvas.drawRoundRect(left, top, right, top + tagH, 3f * density, 3f * density, tagBgPaint)
            canvas.drawText(name, left + pad, top + tagH - 3f * density, tagTextPaint)
            // pointer from tag down to the exact frequency on the scale line
            canvas.drawLine(x, top + tagH, x, scaleH.toFloat(), tagBgPaint)
            tagHits.add(android.graphics.RectF(left, top, right, top + tagH) to tag)
            lastEnd[row] = right
        }
    }

    private fun freqToXAbs(freq: Long, w: Int): Float {
        val bandStart = centerFreq - sampRate / 2
        val frac = (freq - bandStart).toFloat() / sampRate
        val viewFrac = (frac - viewStart) / (viewEnd - viewStart)
        return viewFrac * w
    }

    private fun drawSpectrum(canvas: Canvas, w: Int, h: Int) {
        val frame = lastFrame
        if (frame.isEmpty()) return
        val n = frame.size
        val startBin = (viewStart * n).toInt().coerceIn(0, n - 2)
        val endBin = (viewEnd * n).toInt().coerceIn(startBin + 1, n)
        val bins = endBin - startBin
        var prevX = 0f
        var prevY = h.toFloat()
        for (x in 0 until w) {
            val from = startBin + (x.toLong() * bins / w).toInt()
            val to = (startBin + ((x + 1).toLong() * bins / w).toInt()).coerceAtMost(endBin).coerceAtLeast(from + 1)
            var v = frame[from]
            for (i in from + 1 until to) if (frame[i] > v) v = frame[i]
            val t = ((v - minDb) / (maxDb - minDb)).coerceIn(0f, 1f)
            val y = h - t * h
            if (x > 0) canvas.drawLine(prevX, prevY, x.toFloat(), y, spectrumPaint)
            prevX = x.toFloat()
            prevY = y
        }
    }

    private fun drawWaterfall(canvas: Canvas, w: Int, top: Int, h: Int) {
        val bm = bitmap ?: return
        val sx0 = (viewStart * bmWidth).toInt().coerceIn(0, bmWidth - 1)
        val sx1 = (viewEnd * bmWidth).toInt().coerceIn(sx0 + 1, bmWidth)

        // newest rows first: draw slice [0..rowPointer) below slice [rowPointer..rowsFilled)
        val newest = rowPointer
        if (newest > 0) {
            srcRect.set(sx0, 0, sx1, newest)
            val sliceH = (newest.toFloat() / WATERFALL_ROWS * h).toInt()
            dstRect.set(0, top, w, top + sliceH)
            // draw newest slice flipped so latest row is at the top of the waterfall
            canvas.save()
            canvas.scale(1f, -1f, 0f, top + sliceH / 2f)
            canvas.drawBitmap(bm, srcRect, dstRect, bmPaint)
            canvas.restore()
        }
        if (rowsFilled > newest) {
            val older = rowsFilled - newest
            srcRect.set(sx0, newest, sx1, rowsFilled)
            val offsetY = top + (newest.toFloat() / WATERFALL_ROWS * h).toInt()
            val sliceH = (older.toFloat() / WATERFALL_ROWS * h).toInt()
            dstRect.set(0, offsetY, w, offsetY + sliceH)
            canvas.save()
            canvas.scale(1f, -1f, 0f, offsetY + sliceH / 2f)
            canvas.drawBitmap(bm, srcRect, dstRect, bmPaint)
            canvas.restore()
        }
    }

    private fun drawOverlay(canvas: Canvas, w: Int, h: Int, scaleH: Int) {
        if (centerFreq == 0L || sampRate == 0) return
        val top = scaleH.toFloat()
        val tuneX = freqToX(tunedFreq, w) ?: return
        val lowX = freqToX(tunedFreq + passbandLow, w)
        val highX = freqToX(tunedFreq + passbandHigh, w)
        if (lowX != null && highX != null) {
            canvas.drawRect(lowX, top, highX, h.toFloat(), passbandPaint)
        }
        canvas.drawLine(tuneX, top, tuneX, h.toFloat(), tunePaint)
    }

    private fun freqToX(freq: Long, w: Int): Float? {
        val bandStart = centerFreq - sampRate / 2
        val frac = (freq - bandStart).toFloat() / sampRate
        val viewFrac = (frac - viewStart) / (viewEnd - viewStart)
        if (viewFrac < -0.1f || viewFrac > 1.1f) return null
        return viewFrac * w
    }

    private fun xToFreq(x: Float): Long {
        val viewFrac = x / width
        val frac = viewStart + viewFrac * (viewEnd - viewStart)
        val bandStart = centerFreq - sampRate / 2
        return bandStart + (frac * sampRate).toLong()
    }

    // ── gestures ──

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val focusFrac = viewStart + (detector.focusX / width) * (viewEnd - viewStart)
            var span = (viewEnd - viewStart) / detector.scaleFactor
            span = span.coerceIn(MIN_SPAN, 1f)
            val focusRel = (detector.focusX / width)
            viewStart = (focusFrac - focusRel * span).coerceIn(0f, 1f - span)
            viewEnd = viewStart + span
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (e.y <= scaleBandH) {
                // bookmark tag tap (with a little slack around the tag box)
                val slack = 6 * density
                val hit = tagHits.firstOrNull {
                    e.x >= it.first.left - slack && e.x <= it.first.right + slack &&
                        e.y >= it.first.top - slack && e.y <= it.first.bottom + slack
                } ?: tagHits.minByOrNull { kotlin.math.abs(e.x - it.first.centerX()) }
                    ?.takeIf { kotlin.math.abs(e.x - it.first.centerX()) < 24 * density }
                hit?.let { onTagTap?.invoke(it.second) }
                return true
            }
            if (sampRate > 0) onTune?.invoke(xToFreq(e.x))
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            val span = viewEnd - viewStart
            if (span >= 1f) return false
            val shift = dx / width * span
            viewStart = (viewStart + shift).coerceIn(0f, 1f - span)
            viewEnd = viewStart + span
            invalidate()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            viewStart = 0f
            viewEnd = 1f
            invalidate()
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    companion object {
        const val WATERFALL_ROWS = 400
        const val MAX_BITMAP_WIDTH = 2048
        const val SPECTRUM_FRACTION = 0.25f
        const val MIN_SPAN = 0.02f
    }
}
