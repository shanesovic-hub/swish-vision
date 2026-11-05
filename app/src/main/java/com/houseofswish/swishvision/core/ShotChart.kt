package com.houseofswish.swishvision.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Shot chart on a high school half court, split into the 20 shooting spots the players practise from
 * (see [Zone]). Free throws aren't on the chart (they get their own line).
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

    /**
     * The 20 shooting spots: 5, 10, 15 and 20 ft from the rim, at 5 spots round the court (left baseline,
     * left wing, middle, right wing, right baseline). Distance bands: under 7.5 ft = 5 ft, 7.5-12.5 = 10 ft,
     * 12.5-17.5 = 15 ft, 17.5 and out = 20 ft. Spots by angle from straight out: middle within 22.5 deg,
     * wings 22.5-67.5, baselines beyond. [from]/[to]: angle range; [inner]/[outer]: radius range (ft);
     * [labelDeg]: where its numbers go. Same rules as the website (web/public/js/svstats.js).
     */
    enum class Zone(val key: String, val label: String, val band: Int, val spot: Int,
                    val from: Float, val to: Float, val inner: Float, val outer: Float, val labelDeg: Float) {
        D5_LB("d5_lb", "5 ft \u00B7 Left baseline", 5, 0, -180f, -67.5f, 0f, 7.5f, -84f),
        D5_LW("d5_lw", "5 ft \u00B7 Left wing", 5, 1, -67.5f, -22.5f, 0f, 7.5f, -45f),
        D5_M("d5_m", "5 ft \u00B7 Middle", 5, 2, -22.5f, 22.5f, 0f, 7.5f, 0f),
        D5_RW("d5_rw", "5 ft \u00B7 Right wing", 5, 3, 22.5f, 67.5f, 0f, 7.5f, 45f),
        D5_RB("d5_rb", "5 ft \u00B7 Right baseline", 5, 4, 67.5f, 180f, 0f, 7.5f, 84f),
        D10_LB("d10_lb", "10 ft \u00B7 Left baseline", 10, 0, -180f, -67.5f, 7.5f, 12.5f, -84f),
        D10_LW("d10_lw", "10 ft \u00B7 Left wing", 10, 1, -67.5f, -22.5f, 7.5f, 12.5f, -45f),
        D10_M("d10_m", "10 ft \u00B7 Middle", 10, 2, -22.5f, 22.5f, 7.5f, 12.5f, 0f),
        D10_RW("d10_rw", "10 ft \u00B7 Right wing", 10, 3, 22.5f, 67.5f, 7.5f, 12.5f, 45f),
        D10_RB("d10_rb", "10 ft \u00B7 Right baseline", 10, 4, 67.5f, 180f, 7.5f, 12.5f, 84f),
        D15_LB("d15_lb", "15 ft \u00B7 Left baseline", 15, 0, -180f, -67.5f, 12.5f, 17.5f, -84f),
        D15_LW("d15_lw", "15 ft \u00B7 Left wing", 15, 1, -67.5f, -22.5f, 12.5f, 17.5f, -45f),
        D15_M("d15_m", "15 ft \u00B7 Middle", 15, 2, -22.5f, 22.5f, 12.5f, 17.5f, 0f),
        D15_RW("d15_rw", "15 ft \u00B7 Right wing", 15, 3, 22.5f, 67.5f, 12.5f, 17.5f, 45f),
        D15_RB("d15_rb", "15 ft \u00B7 Right baseline", 15, 4, 67.5f, 180f, 12.5f, 17.5f, 84f),
        D20_LB("d20_lb", "20 ft \u00B7 Left baseline", 20, 0, -180f, -67.5f, 17.5f, 80f, -84f),
        D20_LW("d20_lw", "20 ft \u00B7 Left wing", 20, 1, -67.5f, -22.5f, 17.5f, 80f, -45f),
        D20_M("d20_m", "20 ft \u00B7 Middle", 20, 2, -22.5f, 22.5f, 17.5f, 80f, 0f),
        D20_RW("d20_rw", "20 ft \u00B7 Right wing", 20, 3, 22.5f, 67.5f, 17.5f, 80f, 45f),
        D20_RB("d20_rb", "20 ft \u00B7 Right baseline", 20, 4, 67.5f, 180f, 17.5f, 80f, 84f),
    }

    /** Angle from straight out (0) toward the shooter's right (+) or left (-), in degrees; 90 = along the baseline. */
    fun angle(x: Float, y: Float): Float = Math.toDegrees(atan2(x.toDouble(), y.toDouble())).toFloat()

    fun zoneOf(x: Float, y: Float): Zone {
        val d = hypot(x, y)
        val a = angle(x, y)
        val aa = abs(a)
        val band = when { d < 7.5f -> 5; d < 12.5f -> 10; d < 17.5f -> 15; else -> 20 }
        val spot = when { aa < 22.5f -> 2; aa < 67.5f -> if (a < 0) 1 else 3; else -> if (a < 0) 0 else 4 }
        return Zone.values().first { it.band == band && it.spot == spot }
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
        val (good, ok) = when (zone.band) {
            5 -> 60f to 45f
            10 -> 45f to 35f
            15 -> 40f to 30f
            else -> 35f to 27f
        }
        return if (pct >= good) 1 else if (pct >= ok) 0 else -1
    }
}
