package com.houseofswish.swishvision.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Shot chart zones, like an NBA shot chart but sized to the app's own spot rules: inside 7.3 ft is
 * "at the rim", 7.3-17.5 ft is mid-range (5 zones), 17.5 ft and out is three (5 zones). Free throws
 * aren't on the chart (they get their own line).
 *
 * Court position: feet from the middle of the rim, x to the shooter's right (facing the hoop), y out
 * toward the free-throw line. Pure logic, no Android.
 */
object ShotChart {
    const val RIM_FT = 7.3f
    const val THREE_FT = 17.5f

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
            d < THREE_FT -> when {
                aa < 22.5f -> Zone.MID_CENTER
                aa < 60f -> if (a < 0) Zone.MID_LEFT else Zone.MID_RIGHT
                else -> if (a < 0) Zone.MID_LEFT_BASE else Zone.MID_RIGHT_BASE
            }
            else -> when {
                aa < 25f -> Zone.THREE_TOP
                aa < 68f -> if (a < 0) Zone.THREE_LEFT else Zone.THREE_RIGHT
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
