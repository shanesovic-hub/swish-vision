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
    /**
     * [x], [z]: metres from the rim in the camera's view (sideways, away from the camera).
     * [courtX], [courtY]: feet from the rim on the court (shooter's right, out toward the free-throw line);
     * only once the free-throw spot is set, since that's what shows which way the court faces.
     */
    data class Spot(val type: ShotType, val feet: Float, val x: Float, val z: Float, val t: Long,
                    val courtX: Float? = null, val courtY: Float? = null)

    /** Camera-view position (metres from the rim) to court position (feet): the free-throw spot is straight out. */
    fun toCourt(x: Float, z: Float): Pair<Float, Float>? {
        val ft = ftSpot ?: return null
        val n = hypot(ft.first, ft.second)
        if (n < 0.01f) return null
        val ux = ft.first / n; val uz = ft.second / n
        val out = x * ux + z * uz           // toward the free-throw line
        val right = -x * uz + z * ux        // to the right of a shooter facing the hoop
        return Pair(right / FOOT, out / FOOT)
    }

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

    /**
     * Where the shooter stood for a shot that started (went up) at camera time [t].
     * [ball]: the tracked ball's path around then. With more than one person in view (a rebounder under the
     * hoop, someone walking past), the shooter is the one the ball came up from.
     */
    fun spotAt(t: Long, rim: Box, ball: List<TrackPoint> = emptyList()): Spot? {
        val s = samples.lastOrNull { it.t <= t && t - it.t <= LOOK_BACK_MS && it.people.isNotEmpty() } ?: return null
        val shooter = pickShooter(s, rim, releasePoint(ball, t, rim)) ?: return null
        // Their spot over the second and a half before (the shooting motion stretches the box; feet may be out
        // of view at the moment of the shot): same person = a box in about the same place in the picture.
        val seen = ArrayList<Pair<Long, Pair<Float, Float>>>()
        for (o in samples) {
            if (o.t > s.t || s.t - o.t > SMOOTH_MS) continue
            val same = o.people.filter { abs(it.cx - shooter.cx) < 0.5f * shooter.h && abs(top(it) - top(shooter)) < 0.5f * shooter.h }
                .minByOrNull { abs(it.cx - shooter.cx) } ?: continue
            val g = ground(same, o, rim) ?: continue
            seen += o.t to g
        }
        if (seen.isEmpty()) return null // never saw where their feet were
        // Where they were last seen standing, steadied by nearby sightings (not ones from walking up to the spot).
        val (lastT, a) = seen.last()
        val near = seen.map { it.second }.filter { hypot(it.first - a.first, it.second - a.second) < SAME_PLAYER_M }
        val xs = near.map { it.first }; val zs = near.map { it.second }
        val x = xs.sorted()[xs.size / 2]
        val z = zs.sorted()[zs.size / 2]
        val feet = hypot(x, z) / FOOT
        val court = toCourt(x, z)
        return Spot(classify(x, z, feet), feet, x, z, lastT, court?.first, court?.second)
    }

    private fun top(p: Detection) = p.cy - p.h / 2f

    /**
     * Where the ball left the shooter's hands, roughly: the start of its climb toward the rim, followed back
     * while it keeps rising smoothly, then one step further back. Null if it wasn't seen well below the rim.
     */
    private fun releasePoint(ball: List<TrackPoint>, t: Long, rim: Box): Pair<Float, Float>? {
        val u = rim.w
        val pts = ball.filter { it.t <= t + 100 }.sortedBy { it.t }
        if (pts.isEmpty()) return null
        var i = pts.size - 1
        while (i > 0) {
            val cur = pts[i]; val prev = pts[i - 1]
            if (cur.t - prev.t > 200) break
            if (prev.y < cur.y - 0.05f * u) break                       // earlier must be lower (it was rising)
            if (abs(prev.x - cur.x) > 1.5f * u || prev.y - cur.y > 2.5f * u) break // a jump: some other object
            i--
        }
        val first = pts[i]
        if (first.y < rim.y + 1.0f * u) return null                    // only seen near the rim: can't tell
        val next = pts.getOrNull(i + 1)
        return if (next != null && next.t - first.t in 1..200) {
            Pair(first.x + (first.x - next.x), first.y + (first.y - next.y))
        } else Pair(first.x, first.y)
    }

    private fun pickShooter(s: Sample, rim: Box, release: Pair<Float, Float>?): Detection? {
        val people = s.people
        if (people.size == 1) return people[0]
        if (release != null) {
            // Distance from the release point to each person's upper body (head to waist, arms' reach either side).
            val (rx, ry) = release
            val scored = people.map { p ->
                val left = p.cx - p.w / 2f - 0.25f * p.h; val right = p.cx + p.w / 2f + 0.25f * p.h
                val up = top(p) - 0.4f * p.h; val down = top(p) + 0.6f * p.h
                val dx = if (rx < left) left - rx else if (rx > right) rx - right else 0f
                val dy = if (ry < up) up - ry else if (ry > down) ry - down else 0f
                p to hypot(dx, dy) / p.h
            }.sortedBy { it.second }
            val best = scored[0]
            // Clear winner: close to them, and clearly closer than anyone else.
            if (best.second < 0.8f && (scored.size < 2 || scored[1].second - best.second > 0.15f)) return best.first
        }
        // Can't tell from the ball: the one furthest from the rim (a rebounder waits under it).
        return people.maxByOrNull { p -> ground(p, s, rim)?.let { hypot(it.first, it.second) } ?: -1f }
            ?.takeIf { ground(it, s, rim) != null }
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
