package com.houseofswish.swishvision

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.View
import com.houseofswish.swishvision.core.Result
import com.houseofswish.swishvision.core.Shot
import com.houseofswish.swishvision.core.ShotChart
import com.houseofswish.swishvision.core.ShotChart.Zone
import com.houseofswish.swishvision.core.TypeLine
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Half-court shot chart: hoop at the top, zones coloured by how well the player shot there
 * (green hot, yellow OK, red cold, grey no shots), each zone's made/attempted and percentage,
 * and a dot for every shot (green = make, red = miss).
 */
class ShotChartView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    private var shots: List<Shot> = emptyList()
    private var lines: Map<Zone, TypeLine> = emptyMap()

    fun setShots(all: List<Shot>) {
        shots = ShotChart.charted(all)
        lines = ShotChart.zoneLines(all)
        invalidate()
    }

    // Court window in feet: x -25..25 (sideline to sideline), y from the baseline (rim 5.25 ft out) to 28 ft out.
    private val left = -25f
    private val right = 25f
    private val top = -5.25f
    private val bottom = 28f

    private val d = resources.displayMetrics.density
    private val floor = Paint().apply { color = Color.parseColor("#36271A") }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(200, 240, 236, 228)
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(6f * d, 6f * d), 0f)
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.parseColor("#FF7A3C") }
    private companion object { const val LINE = -1f } // "follow the 3-point line" radius
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(200, 240, 236, 228) }
    private val zoneLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = Color.argb(120, 20, 20, 20) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(3f, 0f, 1f, Color.BLACK) }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255); textAlign = Paint.Align.CENTER; setShadowLayer(3f, 0f, 1f, Color.BLACK) }
    private val make = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4ADE80"); style = Paint.Style.FILL }
    private val miss = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F87171"); style = Paint.Style.STROKE; strokeWidth = 1.8f * d }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textAlign = Paint.Align.CENTER; textSize = 14f * d }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val want = (w * (bottom - top) / (right - left)).toInt()
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) want
        else min(want, MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(w, h)
    }

    private var sc = 1f
    private var ox = 0f
    private fun px(x: Float) = ox + (x - left) * sc
    private fun py(y: Float) = (y - top) * sc

    override fun onDraw(c: Canvas) {
        sc = min(width / (right - left), height / (bottom - top))
        ox = (width - (right - left) * sc) / 2f
        val court = RectF(px(left), py(top), px(right), py(bottom))
        c.drawRect(court, floor)
        c.save()
        c.clipRect(court)

        // Zones (radius LINE = follow the 3-point line)
        val far = 60f
        val r1 = ShotChart.RIM_FT
        val cd = ShotChart.CORNER_DEG
        fun zone(z: Zone, a1: Float, a2: Float, rIn: Float, rOut: Float) {
            val l = lines[z]
            fill.color = when {
                l == null -> Color.argb(30, 255, 255, 255)
                else -> when (ShotChart.heat(z, l)) {
                    1 -> Color.argb(150, 34, 170, 90)
                    0 -> Color.argb(150, 235, 200, 80)
                    else -> Color.argb(150, 215, 60, 55)
                }
            }
            val p = wedge(a1, a2, rIn, rOut)
            c.drawPath(p, fill)
            c.drawPath(p, zoneLine)
        }
        zone(Zone.RIM, -180f, 180f, 0f, r1)
        zone(Zone.MID_LEFT_BASE, -180f, -60f, r1, LINE)
        zone(Zone.MID_LEFT, -60f, -22.5f, r1, LINE)
        zone(Zone.MID_CENTER, -22.5f, 22.5f, r1, LINE)
        zone(Zone.MID_RIGHT, 22.5f, 60f, r1, LINE)
        zone(Zone.MID_RIGHT_BASE, 60f, 180f, r1, LINE)
        zone(Zone.THREE_LEFT_CORNER, -180f, -cd, LINE, far)
        zone(Zone.THREE_LEFT, -cd, -25f, LINE, far)
        zone(Zone.THREE_TOP, -25f, 25f, LINE, far)
        zone(Zone.THREE_RIGHT, 25f, cd, LINE, far)
        zone(Zone.THREE_RIGHT_CORNER, cd, 180f, LINE, far)

        // High school court lines: 12 ft lane, free-throw line 13.75 ft out with its circle (bottom half
        // dashed), 3-point line (straight from the baseline to level with the rim, then the 19'9" arc).
        c.drawRect(px(-6f), py(top), px(6f), py(13.75f), line)
        val ftc = RectF(px(-6f), py(13.75f - 6f), px(6f), py(13.75f + 6f))
        c.drawArc(ftc, 180f, 180f, false, line)
        c.drawArc(ftc, 0f, 180f, false, dashed)
        val t3 = ShotChart.THREE_FT
        c.drawLine(px(-t3), py(top), px(-t3), py(0f), line)
        c.drawLine(px(t3), py(top), px(t3), py(0f), line)
        c.drawArc(RectF(px(-t3), py(-t3), px(t3), py(t3)), 0f, 180f, false, line)
        c.drawLine(px(-3f), py(-1.25f), px(3f), py(-1.25f), line)
        c.drawCircle(px(0f), py(0f), 0.75f * sc, rimPaint)
        c.drawRect(court, line)
        c.restore()

        // Shots
        val dot = max(2.5f * d, 0.45f * sc)
        for (s in shots) {
            val x = px(s.courtX!!.coerceIn(left + 0.5f, right - 0.5f))
            val y = py(s.courtY!!.coerceIn(top + 0.5f, bottom - 0.5f))
            if (s.result == Result.MAKE) c.drawCircle(x, y, dot, make)
            else { c.drawLine(x - dot, y - dot, x + dot, y + dot, miss); c.drawLine(x - dot, y + dot, x + dot, y - dot, miss) }
        }

        // Labels: made/attempted and percentage in each zone with shots.
        label.textSize = max(11f * d, 1.5f * sc)
        small.textSize = max(9f * d, 1.15f * sc)
        val spots = mapOf(
            Zone.RIM to (0f to 3.6f),
            Zone.MID_LEFT_BASE to (-13.5f to 0.8f), Zone.MID_LEFT to (-9.5f to 10.5f), Zone.MID_CENTER to (0f to 10.4f),
            Zone.MID_RIGHT to (9.5f to 10.5f), Zone.MID_RIGHT_BASE to (13.5f to 0.8f),
            Zone.THREE_LEFT_CORNER to (-22.4f to -0.6f), Zone.THREE_LEFT to (-17.5f to 16.5f), Zone.THREE_TOP to (0f to 23.5f),
            Zone.THREE_RIGHT to (17.5f to 16.5f), Zone.THREE_RIGHT_CORNER to (22.4f to -0.6f),
        )
        for ((z, l) in lines) {
            val (fx, fy) = spots.getValue(z)
            val pct = Math.round(100f * l.made / l.attempted)
            c.drawText("${l.made}/${l.attempted}", px(fx), py(fy), label)
            c.drawText("$pct%", px(fx), py(fy) + small.textSize * 1.15f, small)
        }
        if (shots.isEmpty()) {
            c.drawText("No shots on the chart yet", court.centerX(), court.centerY(), empty)
        }
    }

    /** Ring segment between angles [a1]..[a2] (degrees from straight out, + = shooter's right) and radii (feet). */
    private fun wedge(a1: Float, a2: Float, rIn: Float, rOut: Float): Path {
        val p = Path()
        val steps = 48
        fun r(v: Float, deg: Float) = if (v == LINE) ShotChart.lineAt(deg) else v
        for (i in 0..steps) {
            val deg = a1 + (a2 - a1) * i / steps
            val a = Math.toRadians(deg.toDouble()); val rr = r(rOut, deg)
            val x = px((rr * sin(a)).toFloat()); val y = py((rr * cos(a)).toFloat())
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        for (i in steps downTo 0) {
            val deg = a1 + (a2 - a1) * i / steps
            val a = Math.toRadians(deg.toDouble()); val rr = r(rIn, deg)
            p.lineTo(px((rr * sin(a)).toFloat()), py((rr * cos(a)).toFloat()))
        }
        p.close()
        return p
    }
}
