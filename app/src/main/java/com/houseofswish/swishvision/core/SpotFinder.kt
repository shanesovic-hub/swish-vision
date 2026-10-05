package com.houseofswish.swishvision.core

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Works out where a shot was taken from: layup, mid-range, free throw or three.
 *
 * The person finder gives a box around each player a few times a second. From one phone camera:
 *  - how far away a player is comes from how tall they look (a person ~1.75 m tall, or as calibrated),
 *  - how far away the rim is comes from how wide it looks (an 18 in rim, ~0.495 m across the outside),
 *  - left/right comes from where they are in the picture.
 * That puts the shooter and the rim on a map of the court, seen from above, and the distance between them
 * picks the shot type. Standing on the free-throw line and pressing "Set FT spot" once fixes the scale
 * (the line is 13 ft 9 in from the middle of the rim) and teaches where free throws are taken from.
 *
 * Pure logic, no Android, so it can be tested.
 */
class SpotFinder(
    val shooterHeightM: Float = 1.75f,
    val layupMaxFt: Float = 7.3f,     // closer than this: layup
    val threeMinFt: Float = 17.5f,   // this far or further: three
    val ftRadiusFt: Float = 3f,      // this close to the free-throw spot: free throw (once it's set)
    val focal: Float = 0.64f,        // camera focal length / picture width (phone main cameras ~0.6-0.75)
) {
    data class Spot(val type: ShotType, val feet: Float, val x: Float, val z: Float, val t: Long)

    private class Sample(val t: Long, val people: List<Detection>, val frameW: Int, val frameH: Int)
    private val samples = ArrayDeque<Sample>()

    /** Multiplies every distance (1 until the free-throw spot is set). */
    var scale = 1f
        private set
    /** Free-throw spot on the court map, metres from the rim (null until set). */
    var ftSpot: Pair<Float, Float>? = null
        private set
    val calibrated: Boolean get() = ftSpot != null

    fun reset() {
        samples.clear(); scale = 1f; ftSpot = null
    }

    /** People found at camera time [t] (boxes in frame pixels). */
    fun add(t: Long, people: List<Detection>, frameW: Int, frameH: Int) {
        samples.addLast(Sample(t, people, frameW, frameH))
        while (samples.isNotEmpty() && t - samples.first().t > KEEP_MS) samples.removeFirst()
    }

    /** Court map position of a person, metres from the rim (x sideways, z away from the camera); null if unusable. */
    private fun ground(p: Detection, s: Sample, rim: Box): Pair<Float, Float>? {
        if (p.h < 0.08f * s.frameH) return null                 // too small to measure
        if (p.cy + p.h / 2f > s.frameH * 0.985f) return null    // feet cut off by the bottom edge: height unknown
        if (p.cy - p.h / 2f < s.frameH * 0.005f) return null    // head cut off
        val f = focal * s.frameW
        val z = f * shooterHeightM / p.h
        val x = (p.cx - s.frameW / 2f) * z / f
        val zr = f * RIM_M / rim.w
        val xr = (rim.cx - s.frameW / 2f) * zr / f
        return Pair((x - xr) * scale, (z - zr) * scale)
    }

    /** Where the shooter stood for a shot that started (went up) at camera time [t]. */
    fun spotAt(t: Long, rim: Box): Spot? {
        // The latest moment before the shot with a usable player in view.
        var anchor: Pair<Float, Float>? = null
        var anchorT = 0L
        for (s in samples.reversed()) {
            if (s.t > t) continue
            if (t - s.t > LOOK_BACK_MS) break
            val best = s.people.sortedByDescending { it.score }.firstNotNullOfOrNull { ground(it, s, rim) } ?: continue
            anchor = best; anchorT = s.t
            break
        }
        val a = anchor ?: return null
        // Smooth it: the same player's spot over the second and a half before (shooting motion moves the box).
        val xs = ArrayList<Float>(); val zs = ArrayList<Float>()
        for (s in samples) {
            if (s.t > anchorT || anchorT - s.t > SMOOTH_MS) continue
            val g = s.people.mapNotNull { ground(it, s, rim) }.minByOrNull { hypot(it.first - a.first, it.second - a.second) } ?: continue
            if (hypot(g.first - a.first, g.second - a.second) < SAME_PLAYER_M) { xs += g.first; zs += g.second }
        }
        val x = if (xs.isEmpty()) a.first else xs.sorted()[xs.size / 2]
        val z = if (zs.isEmpty()) a.second else zs.sorted()[zs.size / 2]
        val feet = hypot(x, z) / FOOT
        return Spot(classify(x, z, feet), feet, x, z, anchorT)
    }

    private fun classify(x: Float, z: Float, feet: Float): ShotType {
        val ft = ftSpot
        return when {
            feet < layupMaxFt -> ShotType.LAYUP
            ft != null && hypot(x - ft.first, z - ft.second) / FOOT < ftRadiusFt -> ShotType.FT
            feet >= threeMinFt -> ShotType.THREE
            else -> ShotType.MID
        }
    }

    /**
     * The player is standing on the free-throw line right now: learn the scale and the free-throw spot.
     * Returns false if no one could be measured in the last moment (feet out of view, nobody there).
     */
    fun setFreeThrowSpot(now: Long, rim: Box): Boolean {
        val s = samples.lastOrNull { now - it.t <= 1500 } ?: return false
        val p = s.people.sortedByDescending { it.score }.firstOrNull { ground(it, s, rim) != null } ?: return false
        val old = scale
        scale = 1f
        val g = ground(p, s, rim)
        if (g == null) { scale = old; return false }
        val d = hypot(g.first, g.second)
        if (d < 1f) { scale = old; return false } // standing under the rim: not the free-throw line
        scale = FT_M / d
        ftSpot = Pair(g.first * scale, g.second * scale)
        return true
    }

    companion object {
        const val RIM_M = 0.495f        // rim width, outside edge to outside edge
        const val FT_M = 4.19f          // free-throw line to the middle of the rim (13 ft 9 in)
        const val FOOT = 0.3048f
        const val KEEP_MS = 20_000L
        const val LOOK_BACK_MS = 3000L
        const val SMOOTH_MS = 1500L
        const val SAME_PLAYER_M = 1.2f
    }
}
