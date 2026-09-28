package com.houseofswish.swishvision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.houseofswish.swishvision.core.Box
import com.houseofswish.swishvision.core.Detection
import com.houseofswish.swishvision.core.Phase
import com.houseofswish.swishvision.core.Result
import com.houseofswish.swishvision.core.Roi
import com.houseofswish.swishvision.core.TrackPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Draws the rim box, the ball and its path over the camera preview, and lets you draw the rim box. */
class OverlayView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** Immutable picture of the latest analysed frame. */
    data class Snapshot(
        val frameW: Int,
        val frameH: Int,
        val balls: List<Detection>,
        val tracked: Detection?,
        val trail: List<TrackPoint>,
        val phase: Phase,
        val roi: Roi?,
    )

    var onRimDrawn: ((Box) -> Unit)? = null
    var settingRim = true
        set(value) { field = value; invalidate() }
    var rim: Box? = null
        set(value) { field = value; invalidate() }
    var showDebug = false
        set(value) { field = value; invalidate() }
    var ringLineFrac = 0.25f

    private var snap: Snapshot? = null
    private var frameW = 1920
    private var frameH = 1080
    private var dragStart: Pair<Float, Float>? = null
    private var dragNow: Pair<Float, Float>? = null
    private var flashText: String? = null
    private var flashColor = Color.WHITE
    private var flashAt = 0L

    private val density = resources.displayMetrics.density
    private val amber = Color.parseColor("#F5A524")
    private val green = Color.parseColor("#4ADE80")
    private val red = Color.parseColor("#F87171")

    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f * density; color = amber
    }
    private val rimFill = Paint().apply { color = Color.argb(26, 245, 165, 36) }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = amber
        pathEffect = DashPathEffect(floatArrayOf(12f, 10f), 0f)
    }
    private val roiPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f * density; color = Color.argb(110, 237, 241, 244)
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * density; color = Color.argb(150, 237, 241, 244)
    }
    private val ballPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f * density
    }
    private val otherBallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density; color = Color.argb(140, 237, 241, 244)
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = amber; textSize = 12f * density; typeface = Typeface.DEFAULT_BOLD
    }
    private val flashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 64f * density; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
        setShadowLayer(12f, 0f, 4f, Color.BLACK)
    }
    private val flashBg = Paint()

    fun update(s: Snapshot) {
        snap = s
        frameW = s.frameW
        frameH = s.frameH
        invalidate()
    }

    fun flash(r: Result) {
        flashText = if (r == Result.MAKE) "SPLASH!" else "MISS"
        flashColor = if (r == Result.MAKE) green else red
        flashAt = SystemClock.uptimeMillis()
        invalidate()
    }

    // --- frame <-> view mapping (preview is fitCenter in a 16:9 box) ---
    private fun scale() = min(width / frameW.toFloat(), height / frameH.toFloat())
    private fun offX() = (width - frameW * scale()) / 2f
    private fun offY() = (height - frameH * scale()) / 2f
    private fun vx(fx: Float) = offX() + fx * scale()
    private fun vy(fy: Float) = offY() + fy * scale()
    private fun fx(vx: Float) = (vx - offX()) / scale()
    private fun fy(vy: Float) = (vy - offY()) / scale()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!settingRim) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { dragStart = e.x to e.y; dragNow = dragStart }
            MotionEvent.ACTION_MOVE -> dragNow = e.x to e.y
            MotionEvent.ACTION_UP -> {
                val a = dragStart; val b = dragNow
                dragStart = null; dragNow = null
                if (a != null && b != null) {
                    val x0 = fx(min(a.first, b.first)); val x1 = fx(max(a.first, b.first))
                    val y0 = fy(min(a.second, b.second)); val y1 = fy(max(a.second, b.second))
                    if (x1 - x0 > 12 && y1 - y0 > 8) {
                        val box = Box(
                            x0.coerceIn(0f, frameW.toFloat()), y0.coerceIn(0f, frameH.toFloat()),
                            (x1 - x0), (y1 - y0),
                        )
                        rim = box
                        settingRim = false
                        onRimDrawn?.invoke(box)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> { dragStart = null; dragNow = null }
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val s = snap

        if (showDebug && s?.roi != null) {
            val r = s.roi
            c.drawRect(vx(r.x.toFloat()), vy(r.y.toFloat()), vx((r.x + r.w).toFloat()), vy((r.y + r.h).toFloat()), roiPaint)
        }

        rim?.let { b ->
            val rect = RectF(vx(b.x), vy(b.y), vx(b.right), vy(b.bottom))
            c.drawRect(rect, rimFill)
            c.drawRect(rect, rimPaint)
            val ringY = vy(b.y + ringLineFrac * b.h)
            c.drawLine(rect.left, ringY, rect.right, ringY, dashPaint)
            val label = when (s?.phase) {
                Phase.ARMED -> "SHOT UP"
                Phase.PENDING_MAKE -> "IN?"
                Phase.COOLDOWN -> "…"
                else -> "READY"
            }
            c.drawText(label, rect.left, rect.top - 6 * density, labelPaint)
        }

        val a = dragStart; val b = dragNow
        if (settingRim && a != null && b != null) {
            c.drawRect(min(a.first, b.first), min(a.second, b.second), max(a.first, b.first), max(a.second, b.second), dashPaint)
        }

        if (s != null) {
            if (s.trail.size > 1) {
                val p = Path()
                s.trail.forEachIndexed { i, pt -> if (i == 0) p.moveTo(vx(pt.x), vy(pt.y)) else p.lineTo(vx(pt.x), vy(pt.y)) }
                c.drawPath(p, trailPaint)
            }
            for (d in s.balls) {
                if (d === s.tracked) continue
                c.drawCircle(vx(d.cx), vy(d.cy), max(6f, d.w * scale() / 2f), otherBallPaint)
            }
            s.tracked?.let { d ->
                ballPaint.color = when (s.phase) {
                    Phase.ARMED, Phase.PENDING_MAKE -> green
                    else -> amber
                }
                c.drawCircle(vx(d.cx), vy(d.cy), max(8f, d.w * scale() / 2f + 4f), ballPaint)
                if (showDebug) c.drawText(String.format("%.2f", d.score), vx(d.cx) + 10 * density, vy(d.cy), labelPaint)
            }
        }

        // SPLASH / MISS stamp, fades over ~900 ms
        flashText?.let { text ->
            val age = SystemClock.uptimeMillis() - flashAt
            if (age > 900) { flashText = null; return@let }
            val alpha = (255 * (1f - abs(age - 150) / 750f).coerceIn(0f, 1f)).toInt()
            flashBg.color = Color.argb(alpha / 6, Color.red(flashColor), Color.green(flashColor), Color.blue(flashColor))
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), flashBg)
            flashPaint.color = flashColor
            flashPaint.alpha = alpha
            c.drawText(text, width / 2f, height / 2f, flashPaint)
            postInvalidateOnAnimation()
        }
    }
}
