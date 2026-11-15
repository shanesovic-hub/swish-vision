package com.houseofswish.swishvision.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Keeps the rim box on the hoop for the whole session, so the phone can be moved
 * (bumped, turned to a new angle, picked up and set down somewhere else) without
 * anyone having to find the rim again by hand.
 *
 * - No rim yet: finds the hoop (the same steady-hoop check as [HoopFinder]).
 * - Hoop drifts (phone nudged): the box follows it.
 * - Hoop shows up somewhere else and the old spot is empty: the box jumps there.
 * - Hoop not seen near the box for a second: asks for whole-picture looks between
 *   shots ([wantLook]), since after a big move it is outside the crop the ball finder uses.
 * - A box the player drew or dragged is kept the way they set it (same size and
 *   position relative to the hoop) wherever the hoop goes. A box drawn where the
 *   hoop finder can't see any hoop is left alone: the player knows best there.
 *
 * Runs on the analysis thread. All boxes are camera-frame pixels.
 */
class RimKeeper(
    private val needFrames: Int = 8,
    private val minScore: Float = 0.5f,
) {
    enum class Kind { FOUND, FOLLOW, MOVED, BUMPED }

    /** [MOVED]: a new camera spot (shot chart setup no longer applies). [BUMPED]: a jump of under a rim width. */
    data class Change(val rim: Box, val kind: Kind)

    /** The rim box to track with (null while looking for the hoop). */
    var rim: Box? = null
        private set

    /** The hoop box (in [HoopFinder.toRimBox] form) the current rim belongs to. */
    private var ref: Box? = null

    /** The player's box relative to the hoop: null = use the hoop's box as is. */
    private var offset: FloatArray? = null

    /** False for a box the player drew where no hoop was seen: never moved automatically until a hoop matches it. */
    private var learned = true

    private var searching = true
    private val finder = HoopFinder(needFrames, minScore)

    private var lastMatchAt = Long.MIN_VALUE / 2
    private var lastLookAt = Long.MIN_VALUE / 2
    private var cand: Box? = null
    private var candStreak = 0
    private var candSince = 0L
    private var candSeenAt = Long.MIN_VALUE / 2

    // other hoops seen at the same time as the player's (a neighbour's hoop, a gym): never jump to those
    private val others = ArrayList<Pair<Box, Long>>()

    // hoops seen recently, for matching a box the player draws
    private var recentHoops: List<Box> = emptyList()
    private var recentHoopAt = Long.MIN_VALUE / 2

    /** Start looking for the hoop again (no rim until it's found). */
    fun clear(search: Boolean = true) {
        rim = null; ref = null; offset = null; learned = true
        searching = search
        others.clear()
        finder.reset()
        dropCandidate()
    }

    /** The player drew or dragged the box. */
    fun setManual(box: Box, tMs: Long) {
        rim = box
        searching = false
        dropCandidate()
        lastMatchAt = tMs
        val h = if (tMs - recentHoopAt < 2000) recentHoops.firstOrNull { near(box, it) } else null
        if (h != null) {
            ref = h; offset = offsetOf(box, h); learned = true
        } else {
            ref = box; offset = null; learned = false
        }
    }

    /**
     * Should this frame look at the whole picture instead of the crop around the rim?
     * Only between shots ([quiet]: no shot up and no ball near the rim): a few times a
     * second once the hoop hasn't been seen near the box for a second, otherwise now and then.
     */
    fun wantLook(tMs: Long, quiet: Boolean): Boolean {
        if (rim == null) return true
        if (!quiet || !learned) return false
        // Hoop not seen at the box for a second: look a few times a second until it turns up.
        // Otherwise one look every few seconds, to learn where any other hoops in view are.
        val gap = if (tMs - lastMatchAt >= 1000) 250 else 4000
        if (tMs - lastLookAt < gap) return false
        lastLookAt = tMs
        return true
    }

    /**
     * False while the box is probably in the wrong place (the hoop is showing up somewhere
     * else and not at the box): don't count shots against it.
     */
    fun callsTrusted(tMs: Long): Boolean =
        !(cand != null && candStreak >= 2 && tMs - lastMatchAt > 700 && tMs - candSeenAt < 1500)

    /** One analysed frame's hoop detections (frame pixels). Returns what changed, if anything. */
    fun update(tMs: Long, hoops: List<Detection>): Change? {
        val seen = hoops.filter { it.score >= minScore }.sortedByDescending { it.score }
        if (seen.isNotEmpty()) { recentHoops = seen.map { HoopFinder.toRimBox(it) }; recentHoopAt = tMs }

        val current = rim
        if (current == null) {
            if (!searching) return null
            val found = finder.find(hoops) ?: return null
            ref = found; offset = null; learned = true
            rim = found
            lastMatchAt = tMs
            dropCandidate()
            return Change(found, Kind.FOUND)
        }
        val r = ref ?: current
        if (seen.isEmpty()) {
            if (tMs - candSeenAt > 1500) dropCandidate()
            return null
        }
        val boxes = recentHoops

        // Our hoop, where it was or a little way off: follow it.
        val mine = boxes.firstOrNull { near(r, it) }
        if (mine != null) {
            lastMatchAt = tMs
            dropCandidate()
            for (o in boxes) if (o !== mine && !near(mine, o)) noteOther(o, tMs)
            if (!learned) {
                // First time a hoop shows up at the player's box: remember how they placed it.
                offset = offsetOf(current, mine); ref = mine; learned = true
                return null
            }
            val moved = max(abs(mine.x - r.x), abs(mine.y - r.y))
            val grew = abs(mine.w - r.w) / r.w
            if (moved < 0.04f * r.w && grew < 0.06f) return null // jitter: leave it alone
            val k = if (moved > 0.25f * r.w) 0.5f else 0.2f
            val next = HoopFinder.blend(r, mine, k)
            ref = next
            rim = place(next)
            return Change(rim!!, Kind.FOLLOW)
        }
        if (!learned) return null

        // A hoop somewhere else, and none at the box: after it's held steady for half a second, go there.
        others.removeAll { tMs - it.second > 20_000 }
        val b = boxes.firstOrNull { x -> others.none { near(it.first, x) } } ?: return null
        val c = cand
        if (c != null && HoopFinder.iou(c, b) > 0.5f) {
            cand = HoopFinder.blend(c, b, 0.3f); candStreak++
        } else {
            cand = b; candStreak = 1; candSince = tMs
        }
        candSeenAt = tMs
        val steady = cand!!
        val big = hypot(steady.cx - r.cx, steady.cy - r.cy) > r.w || steady.w / r.w !in 0.85f..1.18f
        // A far jump waits a full second (so a second hoop in view can't steal the box while the
        // player's hoop is blocked for a moment); a short one, half a second.
        val hold = if (big) 1000 else 500
        if (candStreak >= 4 && tMs - candSince >= hold && tMs - lastMatchAt >= hold) {
            ref = steady
            rim = place(steady)
            lastMatchAt = tMs
            dropCandidate()
            others.clear()
            return Change(rim!!, if (big) Kind.MOVED else Kind.BUMPED)
        }
        return null
    }

    private fun noteOther(o: Box, tMs: Long) {
        val i = others.indexOfFirst { near(it.first, o) }
        if (i >= 0) others[i] = o to tMs else if (others.size < 6) others += o to tMs
    }

    private fun dropCandidate() {
        cand = null; candStreak = 0
    }

    private fun place(hoop: Box): Box {
        val o = offset ?: return hoop
        return Box(hoop.x + o[0] * hoop.w, hoop.y + o[1] * hoop.w, o[2] * hoop.w, o[3] * hoop.w)
    }

    companion object {
        /** Same hoop: overlapping, or its centre within ~2/3 of a rim width, and about the same size. */
        fun near(a: Box, b: Box): Boolean {
            val size = b.w / a.w
            if (size !in 0.6f..1.65f) return false
            return HoopFinder.iou(a, b) >= 0.3f || hypot(a.cx - b.cx, a.cy - b.cy) < 0.65f * max(a.w, b.w)
        }

        fun offsetOf(box: Box, hoop: Box) = floatArrayOf(
            (box.x - hoop.x) / hoop.w, (box.y - hoop.y) / hoop.w, box.w / hoop.w, box.h / hoop.w,
        )
    }
}
