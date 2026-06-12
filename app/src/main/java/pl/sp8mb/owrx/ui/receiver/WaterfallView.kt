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

    private fun updateLevels(frame: FloatArray) {
        // robust percentile-ish estimate without sorting the whole frame each time
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        var sum = 0f
        for (v in frame) {
            if (v < min) min = v
            if (v > max) max = v
            sum += v
        }
        val mean = sum / frame.size
        val targetMin = mean - 10f
        val targetMax = max + 10f
        // smooth follow
        minDb += (targetMin - minDb) * 0.05f
        maxDb += (targetMax - maxDb) * 0.05f
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
        val spectrumH = (h * SPECTRUM_FRACTION).toInt()
        val waterfallH = h - spectrumH

        drawSpectrum(canvas, w, spectrumH)
        drawWaterfall(canvas, w, spectrumH, waterfallH)
        drawOverlay(canvas, w, h)
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

    private fun drawOverlay(canvas: Canvas, w: Int, h: Int) {
        if (centerFreq == 0L || sampRate == 0) return
        val tuneX = freqToX(tunedFreq, w) ?: return
        val lowX = freqToX(tunedFreq + passbandLow, w)
        val highX = freqToX(tunedFreq + passbandHigh, w)
        if (lowX != null && highX != null) {
            canvas.drawRect(lowX, 0f, highX, h.toFloat(), passbandPaint)
        }
        canvas.drawLine(tuneX, 0f, tuneX, h.toFloat(), tunePaint)
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
