package com.ck.restro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.core.graphics.ColorUtils
import com.ck.restro.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Before / after viewer: drag the glass handle to wipe between the original and the restored
 * photo, pinch or double-tap to zoom in on detail, drag with one finger to look around.
 */
class CompareView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var before: Bitmap? = null
    private var after: Bitmap? = null
    private var split = 0.5f
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var draggingSplit = false

    private val accent = context.attrColor(R.attr.oAccent)
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = context.dp(11f); isFakeBoldText = true
    }
    private val rc = RectF()
    private val tmp = RectF()

    fun set(before: Bitmap?, after: Bitmap?) {
        this.before = before?.let { preview(it) }
        this.after = after?.let { preview(it) }
        if (after == null) split = 0.5f
        invalidate()
    }

    fun clear() { before = null; after = null; zoom = 1f; panX = 0f; panY = 0f; invalidate() }

    val hasResult get() = after != null

    /** GPUs refuse huge bitmaps: keep a view copy no bigger than 3072 px. */
    private fun preview(b: Bitmap): Bitmap {
        val long = max(b.width, b.height)
        if (long <= 3072) return b
        val k = 3072f / long
        return Bitmap.createScaledBitmap(b, (b.width * k).toInt(), (b.height * k).toInt(), true)
    }

    /** Where the photo sits on screen at the current zoom and pan. */
    private fun imageRect(out: RectF): Boolean {
        val b = after ?: before ?: return false
        val vw = width.toFloat(); val vh = height.toFloat()
        val fit = min(vw / b.width, vh / b.height)
        val w = b.width * fit * zoom; val h = b.height * fit * zoom
        clampPan(w, h, vw, vh)
        val l = (vw - w) / 2 + panX; val t = (vh - h) / 2 + panY
        out.set(l, t, l + w, t + h)
        return true
    }

    private fun clampPan(w: Float, h: Float, vw: Float, vh: Float) {
        val mx = max(0f, (w - vw) / 2); val my = max(0f, (h - vh) / 2)
        panX = panX.coerceIn(-mx, mx); panY = panY.coerceIn(-my, my)
    }

    override fun onDraw(canvas: Canvas) {
        if (!imageRect(rc)) return
        val b = before; val a = after
        val sx = rc.left + rc.width() * split
        if (a == null) {
            b?.let { canvas.drawBitmap(it, null, rc, bmpPaint) }
            return
        }
        // restored side
        canvas.save(); canvas.clipRect(sx, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(a, null, rc, bmpPaint); canvas.restore()
        // original side (stretched to the same size, so the difference is plain to see)
        if (b != null) {
            canvas.save(); canvas.clipRect(0f, 0f, sx, height.toFloat())
            canvas.drawBitmap(b, null, rc, bmpPaint); canvas.restore()
        }
        // divider + glass handle
        val top = max(rc.top, 0f); val bot = min(rc.bottom, height.toFloat())
        paint.shader = null
        paint.color = 0xE6FFFFFF.toInt()
        canvas.drawRect(sx - context.dp(1f), top, sx + context.dp(1f), bot, paint)
        val cy = (top + bot) / 2; val r = context.dp(17f)
        paint.color = ColorUtils.setAlphaComponent(0xFF000000.toInt(), 70)
        canvas.drawCircle(sx, cy + context.dp(2f), r, paint)
        paint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(accent, 0xFFFFFFFF.toInt(), 0.25f), 220)
        canvas.drawCircle(sx, cy, r, paint)
        paint.color = 0xFFFFFFFF.toInt()
        val s = context.dp(5f)
        canvas.drawPath(android.graphics.Path().apply {
            moveTo(sx - context.dp(4f), cy - s); lineTo(sx - context.dp(4f) - s, cy); lineTo(sx - context.dp(4f), cy + s); close()
            moveTo(sx + context.dp(4f), cy - s); lineTo(sx + context.dp(4f) + s, cy); lineTo(sx + context.dp(4f), cy + s); close()
        }, paint)
        // labels
        pill(canvas, "BEFORE", max(rc.left, 0f) + context.dp(10f), max(rc.top, 0f) + context.dp(10f), sx > context.dp(90f))
        val aw = text.measureText("AFTER") + context.dp(16f)
        pill(canvas, "AFTER", min(rc.right, width.toFloat()) - aw - context.dp(10f), max(rc.top, 0f) + context.dp(10f),
            sx < width - context.dp(90f))
    }

    private fun pill(c: Canvas, s: String, x: Float, y: Float, show: Boolean) {
        if (!show) return
        val w = text.measureText(s) + context.dp(16f); val h = context.dp(22f)
        tmp.set(x, y, x + w, y + h)
        paint.color = 0x80000000.toInt()
        c.drawRoundRect(tmp, h / 2, h / 2, paint)
        c.drawText(s, x + context.dp(8f), y + h / 2 + text.textSize / 3, text)
    }

    // ---------------- touch ----------------

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val nz = (zoom * d.scaleFactor).coerceIn(1f, 8f)
            // zoom around the fingers
            val cx = d.focusX - width / 2f; val cy = d.focusY - height / 2f
            panX = cx - (cx - panX) * nz / zoom
            panY = cy - (cy - panY) * nz / zoom
            zoom = nz
            invalidate()
            return true
        }
    })

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (zoom > 1.05f) { zoom = 1f; panX = 0f; panY = 0f }
            else {
                val nz = 3f
                val cx = e.x - width / 2f; val cy = e.y - height / 2f
                panX = cx - cx * nz; panY = cy - cy * nz; zoom = nz
            }
            invalidate()
            return true
        }
    })

    private var lastX = 0f
    private var lastY = 0f

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (before == null && after == null) return super.onTouchEvent(e)
        parent?.requestDisallowInterceptTouchEvent(true)
        scaler.onTouchEvent(e)
        taps.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = e.x; lastY = e.y
                imageRect(rc)
                val sx = rc.left + rc.width() * split
                draggingSplit = after != null && (zoom <= 1.01f || abs(e.x - sx) < context.dp(36f))
            }
            MotionEvent.ACTION_POINTER_DOWN -> draggingSplit = false
            MotionEvent.ACTION_MOVE -> if (!scaler.isInProgress && e.pointerCount == 1) {
                if (draggingSplit) {
                    imageRect(rc)
                    val ns = ((e.x - rc.left) / rc.width()).coerceIn(0f, 1f)
                    if ((ns * 20).toInt() != (split * 20).toInt()) Haptics.tick(context, 0.25f)
                    split = ns
                } else {
                    panX += e.x - lastX; panY += e.y - lastY
                }
                lastX = e.x; lastY = e.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                draggingSplit = false
                parent?.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
