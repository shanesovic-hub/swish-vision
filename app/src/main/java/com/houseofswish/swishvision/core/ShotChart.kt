package com.houseofswish.swishvision.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Shot chart zones on a high school half court, like an NBA shot chart: inside 7.3 ft is "at the rim"
 * (the app's layup range), then mid-range out to the high school 3-point line (5 zones), then three
 * (5 zones). Free throws aren't on the chart (they get their own line). Same rules as the website's
 * shot chart (web/public/js/svstats.js).
 *
 * Court position: feet from the middle of the rim, x to the shooter's right (facing the hoop), y out
 * toward the free-throw line. Pure logic, no Android.
 */
object ShotChart {
    const val RIM_FT = 7.3f
    const val THREE_FT = 19.75f   // high school 3-point line: 19 ft 9 in from the middle of the rim
    const val CORNER_DEG = 78f     // threes further round than this are corner threes

    /** Outside the high school 3-point line? It runs straight up from the baseline to level with the rim, then arcs. */
    fun isThree(x: Float, y: Float): Boolean = if (y <= 0f) abs(x) >= THREE_FT else hypot(x, y) >= THREE_FT

    /** Distance from the rim to the 3-point line in direction [a] (degrees from straight out). */
    fun lineAt(a: Float): Float {
        if (abs(a) <= 90f) return THREE_FT
        val s = abs(kotlin.math.sin(Math.toRadians(a.toDouble()))).toFloat()
        return if (s > 0.01f) THREE_FT / s else 200f
    }

    enum class Zone(val key: String, val label: String) {
        RIM("rim", "At the rim"),
        MID_LEFT_BASE("mid_left_baseline", "Left baseline"),
        MID_LEFT("mid_left", "Left elbow"),
        MID_CENTER("mid_center", "Middle"),
        MID_RIGHT("mid_right", "Right elbow"),
        MID_RIGHT_BASE("mid_right_baseline", "Right baseline"),
        THREE_LEFT_CORNER("three_left_corner", "Left corner 3"),
        THREE_LEFT("three_left_wing", "Left wing 3"),
        THREE_TOP("three_top", "Top 3"),
        THREE_RIGHT("three_right_wing", "Right wing 3"),
        THREE_RIGHT_CORNER("three_right_corner", "Right corner 3"),
    }

    /** Angle from straight out (0) toward the shooter's right (+) or left (-), in degrees; 90 = along the baseline. */
    fun angle(x: Float, y: Float): Float = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()

    fun zoneOf(x: Float, y: Float): Zone {
        val d = hypot(x, y)
        val a = angle(x, y)
        val aa = abs(a)
        return when {
            d < RIM_FT -> Zone.RIM
            !isThree(x, y) -> when {
                aa < 22.5f -> Zone.MID_CENTER
                aa < 60f -> if (a < 0) Zone.MID_LEFT else Zone.MID_RIGHT
                else -> if (a < 0) Zone.MID_LEFT_BASE else Zone.MID_RIGHT_BASE
            }
            else -> when {
                aa < 25f -> Zone.THREE_TOP
                aa < CORNER_DEG -> if (a < 0) Zone.THREE_LEFT else Zone.THREE_RIGHT
                else -> if (a < 0) Zone.THREE_LEFT_CORNER else Zone.THREE_RIGHT_CORNER
            }
        }
    }

    /** Shots with a court position (free throws left out). */
    fun charted(shots: List<Shot>): List<Shot> =
        shots.filter { it.courtX != null && it.courtY != null && it.type != ShotType.FT }

    /** Made / attempted per zone, in zone order, only zones with shots. */
    fun zoneLines(shots: List<Shot>): Map<Zone, TypeLine> {
        val c = charted(shots)
        val out = LinkedHashMap<Zone, TypeLine>()
        for (z in Zone.values()) {
            val inZone = c.filter { zoneOf(it.courtX!!, it.courtY!!) == z }
            if (inZone.isNotEmpty()) out[z] = TypeLine(inZone.count { it.result == Result.MAKE }, inZone.size)
        }
        return out
    }

    /** Hot / OK / cold for a zone's shooting percentage (what's good depends on how far out it is). */
    fun heat(zone: Zone, line: TypeLine): Int {
        val pct = 100f * line.made / line.attempted
        val (good, ok) = when (zone) {
            Zone.RIM -> 60f to 45f
            Zone.MID_LEFT_BASE, Zone.MID_LEFT, Zone.MID_CENTER, Zone.MID_RIGHT, Zone.MID_RIGHT_BASE -> 45f to 35f
            else -> 38f to 30f
        }
        return if (pct >= good) 1 else if (pct >= ok) 0 else -1
    }
}
