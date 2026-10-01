package com.houseofswish.swishvision.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Result { MAKE, MISS }

enum class Phase { IDLE, ARMED, PENDING_MAKE, COOLDOWN }

data class TrackPoint(val t: Long, val x: Float, val y: Float, val w: Float) {
    fun cx() = x
}

/**
 * All distances are in rim widths (U), so the logic works at any camera distance.
 * The rim box is what the user draws: snug around the ring and the top of the net.
 */
data class TrackerConfig(
    val ringLineFrac: Float = 0.25f,   // the ring sits this far down the drawn box
    val armAbove: Float = -0.15f,      // ball this far above the box top arms a shot (-0.15 = level with the ring: low cameras see flat arcs)
    val pickAbove: Float = 0.10f,      // a ball this far above the box top is a new shot (takes over from the tracked ball)
    val armHalfWidth: Float = 3.0f,    // ...and within this many U of the rim centre, sideways
    val ringTolerance: Float = -0.15f, // extra width either side of the box still counted "inside" (negative: the ball's centre must
                                       // pass through the middle of the rim; a ball crossing near the edge hits the rim)
    val dropMargin: Float = 0.15f,     // below box bottom by this much = the ball has come down
    val confirmSlack: Float = 0.30f,   // x slack when confirming a make below the net
    val popUp: Float = 0.30f,          // pending make cancelled if ball pops back above ring by this
    val rollOff: Float = 0.60f,        // pending make cancelled if ball drifts this far outside the rim
    val pendingLostMs: Long = 600,     // ball vanished into the net -> make
    val openingLostMs: Long = 600,     // ball vanished right over the opening this long -> make
    val pendingMaxMs: Long = 1500,     // ball lingered in/around the net -> make
    val armedLostMs: Long = 900,       // lost near the rim while armed -> miss
    val armedTimeoutMs: Long = 3000,   // armed but nothing happened -> quietly reset
    val cooldownMs: Long = 1200,       // ignore rim bounces / net shake after a call
    val maxGapMs: Long = 400,          // longer gap starts a new track
    val trailMs: Long = 1500,
    val trailMax: Int = 45,
    val gateRimWidths: Float = 2.0f,   // how far the ball may jump per 33 ms frame
    val ballToRim: Float = 0.52f,      // ball diameter / rim diameter (9.4in / 18in)
    val depthMin: Float = 0.45f,       // sanity check on ball size at the rim
    val depthMax: Float = 1.60f,
    val reacquireMs: Long = 1200,      // ball may vanish near the rim this long and still be connected
    val reacquireWidth: Float = 4.0f,  // ...and reappear at most this many U sideways
    val staticHits: Int = 4,           // a detection seen this often in one spot...
    val staticMs: Long = 500,          // ...over at least this long is not a ball in flight
    val staticForgetMs: Long = 2000,
    val staticGapMs: Long = 300,       // "sitting still" means seen there continuously
    val confirmLostMs: Long = 500,     // a lone sighting after the ball vanished decides after this long
    val fallMin: Float = 0.08f,
    val vanishMs: Long = 150,
    val underNet: Float = 0.70f,
    val reacquireExit: Float = 0.45f,     // after vanishing at the rim, a make must reappear within this many U of the rim centre (shooter's side)
    val reacquireExitFar: Float = 0.70f,  // ...on the far side (a make carries on away from the shooter)
    val reacquireExitMid: Float = 0.60f,  // ...either side, when the shot came straight at the camera or straight away
    // Shot vs. carried ball: a shot drops fast near the rim. Measured in ball-widths per second,
    // so it doesn't matter how close the ball is to the camera.
    val minFall: Float = 3f,           // a lowered or carried ball is slower (6 missed soft shots that peak right at the rim)
    val missWindow: Float = 2.0f,      // a miss has to come down within this many U of the rim centre
    // In line with the camera, a rim-out that drops in front of the net looks like a make.
    // A ball in front of the rim (closer to the camera) looks bigger than it did on the way in.
    val frontRatio: Float = 1.25f,     // exit size vs approach size => in front of the rim => miss
    val frontAbs: Float = 1.10f,       // before calibration: in front if this many times the expected ball-at-the-rim size
    val frontCal: Float = 1.18f,       // after calibration: in front if this many times the ball's usual size coming out of the net
    val calMin: Int = 5,               // makes needed before the ball's usual size is trusted
    // The net slows a make down. A ball that keeps falling fast just under the rim went past the net, not through it.
    val netMaxFall: Float = 9f,        // faster than this (rim widths/s) just under the rim = passed outside the net
    val confirmMs: Long = 300,         // how long to wait for the next sighting before calling it a make anyway
    val confirmZone: Float = 0.5f,     // only check balls this close under the rim box (further down they fall free anyway)
    val frontRatioAfterRim: Float = 1.12f, // stricter if it already bounced on the rim
)

/**
 * Turns a stream of ball detections into MAKE / MISS calls.
 *
 * IDLE -> ARMED when the ball is seen above the rim.
 * ARMED -> PENDING_MAKE when the ball's path crosses the ring line, going down, inside the rim.
 * PENDING_MAKE -> MAKE when the ball comes out under the net (or disappears into it).
 * PENDING_MAKE -> ARMED if it pops back up or rolls off the side (rim-out).
 * ARMED -> MISS when the ball comes down below the rim without a valid crossing.
 */
class ShotTracker(val cfg: TrackerConfig = TrackerConfig()) {
    var phase: Phase = Phase.IDLE
        private set
    val trail = ArrayDeque<TrackPoint>()
    var lastBall: Detection? = null
        private set

    private var armedAt = 0L
    private var pendingAt = 0L
    private var cooldownUntil = 0L
    private var lastSeenAt = Long.MIN_VALUE / 2

    /** Last place the ball was seen above the ring while a shot was up (survives gaps in detection). */
    private var lastAbove: TrackPoint? = null

    /** First sighting after the ball vanished near the rim; confirmed by the next sighting. */
    private var candidate: TrackPoint? = null

    /** The ball vanished while a shot was up: only a falling ball near the rim may decide it now. */
    private var reacquiring = false

    // Per-shot evidence
    private var maxFall = 0f                 // fastest drop seen (ball-widths / s)
    private val approachW = ArrayList<Float>() // ball size on the way in, above the ring
    private var crossX: Float? = null        // where it crossed the ring line going down
    private var bounced = false              // popped back up after reaching the rim
    private var fromSide = 0                 // which side of the rim the shot came from (+1 right, -1 left, 0 straight on)

    private fun newShot() {
        maxFall = 0f; approachW.clear(); crossX = null; bounced = false; fromSide = 0
    }

    /** How far from the rim centre a ball may reappear and still have come through the net. */
    private fun exitLimit(x: Float, rim: Box): Float {
        val side = if (x > rim.cx) 1 else -1
        return when {
            fromSide == 0 -> cfg.reacquireExitMid
            side == fromSide -> cfg.reacquireExit
            else -> cfg.reacquireExitFar
        }
    }

    private fun shotLike() = maxFall >= cfg.minFall

    // A make waiting for one more sighting: did the net slow the ball down?
    private var confirming = false
    private var confirmT = 0L
    private var confirmY = 0f
    private var confirmW = 0f
    private var confirmWhy = ""

    /** The ball came down through the rim: check it isn't in front of the rim, then (maybe) wait to see the net slow it. */
    private fun madeIt(t: Long, y: Float, w: Float, rim: Box, seenThrough: Boolean, why: String): Result? {
        if (inFront(w, rim, seenThrough)) return fire(t, Result.MISS, "in front of the rim")
        if (cfg.netMaxFall > 0f && seenThrough && y < rim.bottom + cfg.confirmZone * rim.w) {
            confirming = true
            confirmT = t; confirmY = y; confirmW = w; confirmWhy = why
            phase = Phase.PENDING_MAKE
            pendingAt = t
            return null
        }
        return made(t, w, rim, why)
    }

    private fun made(t: Long, w: Float, rim: Box, why: String): Result {
        makeWidths.addLast(w / rim.w)
        while (makeWidths.size > 15) makeWidths.removeFirst()
        return fire(t, Result.MAKE, why)
    }

    /** The ball came out under the rim: a make, unless it's in front of the rim (bounced toward the camera). */
    private fun throughOrFront(w: Float, rim: Box, seenThrough: Boolean): Result {
        if (inFront(w, rim, seenThrough)) return Result.MISS
        makeWidths.addLast(w / rim.w)
        while (makeWidths.size > 15) makeWidths.removeFirst()
        return Result.MAKE
    }

    /** Ball width (in rim widths) coming out of the net on recent makes: how big a ball at the rim looks for this camera. */
    private val makeWidths = ArrayDeque<Float>()

    /**
     * Is the ball in front of the rim (bounced toward the camera) rather than coming through the net?
     * [seenThrough]: the ball stayed in view all the way past the rim. A ball going through the net usually
     * drops out of view for a moment; one that stays in view the whole way may be falling in front of the net.
     */
    private fun inFront(w: Float, rim: Box, seenThrough: Boolean): Boolean {
        if (seenThrough) {
            val wu = w / rim.w
            if (makeWidths.size >= cfg.calMin) {
                // Bigger than the ball usually looks coming through this rim: it's closer to the camera.
                val ref = makeWidths.sorted()[makeWidths.size / 2]
                if (wu > cfg.frontCal * ref) return true
            } else if (cfg.frontAbs > 0f && wu > cfg.frontAbs * cfg.ballToRim) {
                return true
            }
        }
        // Bigger than it looked on the way in.
        if (approachW.size < 2) return false
        val ref = approachW.sorted()[approachW.size / 2]
        return w / ref > (if (bounced) cfg.frontRatioAfterRim else cfg.frontRatio)
    }

    /** Median of the last few widths, to smooth detection-box noise. */
    private fun recentW(): Float {
        val ws = trail.toList().takeLast(3).map { it.w }.sorted()
        return if (ws.isEmpty()) 0f else ws[ws.size / 2]
    }
    /** Spots where a "ball" sits still (a ball on the floor, a false detection): never the shot. */
    private class Spot(var x: Float, var y: Float, val firstT: Long, var lastT: Long, var hits: Int)
    private val spots = ArrayList<Spot>()

    fun reset() {
        phase = Phase.IDLE
        trail.clear()
        lastBall = null
        lastAbove = null
        candidate = null
        reacquiring = false
        newShot()
        spots.clear()
        makeWidths.clear()
        confirming = false
        armedAt = 0L; pendingAt = 0L; cooldownUntil = 0L
        lastSeenAt = Long.MIN_VALUE / 2
    }

    private fun spotRadius(d: Detection, rim: Box?) = 0.35f * (rim?.w ?: (d.w * 2f))

    private fun observeSpots(t: Long, dets: List<Detection>, rim: Box?) {
        spots.removeAll { t - it.lastT > cfg.staticForgetMs }
        for (d in dets) {
            val r = spotRadius(d, rim)
            val s = spots.firstOrNull { abs(it.x - d.cx) < r && abs(it.y - d.cy) < r }
            if (s != null && t - s.lastT > cfg.staticGapMs) {
                // seen here before, but not continuously (e.g. every make passes the same spot): start over
                spots.remove(s)
                spots += Spot(d.cx, d.cy, t, t, 1)
            } else if (s != null) {
                s.hits++; s.lastT = t
                s.x += (d.cx - s.x) * 0.2f; s.y += (d.cy - s.y) * 0.2f
            } else if (spots.size < 20) {
                spots += Spot(d.cx, d.cy, t, t, 1)
            }
        }
    }

    private fun isStatic(d: Detection, rim: Box?): Boolean {
        val r = spotRadius(d, rim)
        return spots.any {
            abs(it.x - d.cx) < r && abs(it.y - d.cy) < r &&
                it.hits >= cfg.staticHits && it.lastT - it.firstT >= cfg.staticMs
        }
    }

    /** Feed one analysed frame. Returns a call when a shot is decided. */
    fun update(t: Long, detections: List<Detection>, rim: Box?): Result? {
        observeSpots(t, detections, rim)
        val moving = detections.filterNot { isStatic(it, rim) }
        val ball = pick(t, moving, rim)
        lastBall = ball
        if (ball != null) {
            val prev = trail.lastOrNull()
            val broken = prev == null || t - prev.t > cfg.vanishMs
            if (prev != null && t - prev.t > cfg.maxGapMs) trail.clear()
            if (broken && phase == Phase.ARMED && lastAbove != null) reacquiring = true
            trail.addLast(TrackPoint(t, ball.cx, ball.cy, ball.w))
            while (trail.size > cfg.trailMax) trail.removeFirst()
            lastSeenAt = t
        }
        while (trail.isNotEmpty() && t - trail.first().t > cfg.trailMs) trail.removeFirst()

        if (rim == null) {
            phase = Phase.IDLE
            return null
        }
        if (phase == Phase.COOLDOWN) {
            if (t < cooldownUntil) return null
            phase = Phase.IDLE
        }
        return if (ball == null) onLost(t, rim) else onSeen(t, ball, rim)
    }

    private fun onSeen(t: Long, ball: Detection, rim: Box): Result? {
        val u = rim.w
        val x = ball.cx
        val y = ball.cy
        val ringY = rim.y + cfg.ringLineFrac * rim.h
        val p0 = if (trail.size >= 2) trail[trail.size - 2] else null
        if ((phase == Phase.ARMED || phase == Phase.PENDING_MAKE) && p0 != null && t - p0.t in 1..150) {
            val bw = max(1f, (ball.w + p0.w) / 2f)
            val fall = (y - p0.y) / bw / ((t - p0.t) / 1000f)
            if (fall > maxFall) maxFall = fall
        }

        when (phase) {
            Phase.IDLE -> {
                if (y < rim.y - cfg.armAbove * u && abs(x - rim.cx) < cfg.armHalfWidth * u && depthOk(rim)) {
                    phase = Phase.ARMED
                    armedAt = t
                    newShot()
                    fromSide = if (x > rim.cx + 0.3f * u) 1 else if (x < rim.cx - 0.3f * u) -1 else 0
                    approachW += ball.w
                    lastAbove = TrackPoint(t, x, y, ball.w)
                }
            }
            Phase.ARMED -> {
                if (t - armedAt > cfg.armedTimeoutMs) { toIdle(); return null }
                if (y <= ringY) {
                    // Above the ring: the shot is still coming down (or bounced back up).
                    reacquiring = false
                    candidate = null
                    lastAbove = TrackPoint(t, x, y, ball.w)
                    if (!bounced && approachW.size < 12) approachW += ball.w
                    if (y < rim.y - cfg.armAbove * u) armedAt = t
                    return null
                }
                val a = lastAbove
                if (reacquiring) {
                    // It vanished near the rim (blur, backboard, net). Wait for a falling ball near the rim.
                    if (a == null || t - a.t > cfg.reacquireMs) { toIdle(); return null } // never came back: no call
                    val c = candidate
                    val same = c != null && p0 === c && abs(x - c.x) < 1.5f * u
                    if (same && y - c!!.y >= cfg.fallMin * u) {
                        reacquiring = false
                        candidate = null
                        return decide(t, a, x, y, rim, reacquired = true, exitX = c.x) // connect last-seen-above to here
                    }
                    if (same || abs(x - a.x) <= cfg.reacquireWidth * u) candidate = trail.last()
                    return null
                }
                if (p0 != null && p0.y <= ringY) return decide(t, p0, x, y, rim)
                if (y > rim.bottom + cfg.dropMargin * u) {
                    val where = crossX ?: x
                    if (!shotLike() || abs(where - rim.cx) > cfg.missWindow * u) { toIdle(); return null }
                    return fire(t, Result.MISS, "came down beside the rim")
                }
            }
            Phase.PENDING_MAKE -> {
                if (confirming) {
                    val dt = t - confirmT
                    if (dt <= 0) return null
                    confirming = false
                    val fall = (y - confirmY) / u / (dt / 1000f)
                    if (dt <= cfg.confirmMs && fall > cfg.netMaxFall) return fire(t, Result.MISS, "fell past the net")
                    return made(t, confirmW, rim, confirmWhy)
                }
                val dx = abs(x - rim.cx)
                if (y > rim.bottom && dx < (0.5f + cfg.confirmSlack) * u) {
                    return madeIt(t, y, recentW(), rim, seenThrough = true, why = "came out under the net")
                }
                if (y < ringY - cfg.popUp * u || dx > (0.5f + cfg.rollOff) * u) {
                    bounced = true // hit the rim
                    phase = Phase.ARMED
                    armedAt = t
                    return null
                }
                if (y > rim.bottom + cfg.dropMargin * u) return fire(t, Result.MISS, "dropped outside the net")
                if (t - pendingAt > cfg.pendingMaxMs) return fire(t, Result.MAKE, "stayed in the net")
            }
            Phase.COOLDOWN -> Unit
        }
        return null
    }

    /** The ball went from [from] (above the ring) to (x, y) (below it): did that path go through the rim? */
    private fun decide(t: Long, from: TrackPoint, x: Float, y: Float, rim: Box, reacquired: Boolean = false, exitX: Float = x): Result? {
        val ringY = rim.y + cfg.ringLineFrac * rim.h
        val xc = xAtLine(from.x, from.y, x, y, ringY)
        // After it vanished, a ball dropping out of the bottom of the net, right under the rim, went through.
        if (!shotLike()) { toIdle(); return null } // came down too slowly: carried, not shot
        crossX = xc
        val outOfNet = reacquired && y > rim.bottom && abs(x - rim.cx) < cfg.underNet * rim.w
        // After vanishing at the rim the path is a guess: a ball that reappears off to the side bounced out.
        val exitOk = !reacquired || abs(exitX - rim.cx) <= exitLimit(exitX, rim) * rim.w
        if (exitOk && (outOfNet || insideRing(xc, rim))) {
            if (!depthOk(rim)) { toIdle(); return null } // wrong size for a ball at the rim: not our shot
            if (y > rim.bottom) return madeIt(t, y, recentW(), rim, seenThrough = !reacquired, why = if (outOfNet) "reappeared under the net" else "went through the ring")
            phase = Phase.PENDING_MAKE
            pendingAt = t
            return null
        }
        if (y > rim.bottom + cfg.dropMargin * rim.w) {
            if (abs(xc - rim.cx) > cfg.missWindow * rim.w) { toIdle(); return null } // nowhere near this rim
            return fire(t, Result.MISS, "crossed outside the ring")
        }
        return null
    }

    private fun toIdle() {
        phase = Phase.IDLE
        confirming = false
        lastAbove = null
        candidate = null
        reacquiring = false
        newShot()
    }

    private fun onLost(t: Long, rim: Box): Result? {
        val gap = t - lastSeenAt
        val u = rim.w
        when (phase) {
            Phase.PENDING_MAKE -> {
                if (confirming) {
                    // Not seen again: it's in the net.
                    if (t - confirmT > cfg.confirmMs) { confirming = false; return made(t, confirmW, rim, confirmWhy) }
                    return null
                }
                if (gap > cfg.pendingLostMs) return fire(t, Result.MAKE, "vanished into the net")
            }
            Phase.ARMED -> {
                if (t - armedAt > cfg.armedTimeoutMs) { toIdle(); return null }
                val c = candidate
                val a = lastAbove
                if (reacquiring && c != null && a != null && t - c.t > cfg.confirmLostMs) {
                    // Only one sighting after it vanished. Count it only if it was coming out under the net.
                    candidate = null
                    val xc = xAtLine(a.x, a.y, c.x, c.y, rim.y + cfg.ringLineFrac * rim.h)
                    if (shotLike() && insideRing(xc, rim) && c.y > rim.bottom && abs(c.x - rim.cx) < 0.9f * u) {
                        return fire(t, throughOrFront(c.w, rim, seenThrough = false), "one sighting under the net")
                    }
                    return null
                }
                val last = trail.lastOrNull()
                if (!reacquiring && last != null && gap > cfg.pendingLostMs) {
                    val prev = if (trail.size >= 2) trail[trail.size - 2] else null
                    val descending = prev != null && last.y > prev.y
                    val overOpening = last.x > rim.x && last.x < rim.right &&
                        last.y > rim.y - 0.5f * u && last.y < rim.bottom
                    // Vanished right over the opening while falling: it went into the net.
                    if (descending && overOpening && shotLike() && gap > cfg.openingLostMs) return fire(t, Result.MAKE, "vanished over the opening")
                    // Lost beside the rim at rim height (bounced off the side, out of view): a miss.
                    val besideRim = last.y > rim.y - 0.5f * u && abs(last.x - rim.cx) in (0.6f * u)..(2f * u)
                    if (gap > cfg.armedLostMs && besideRim && shotLike()) return fire(t, Result.MISS, "lost beside the rim")
                }
            }
            else -> Unit
        }
        return null
    }

    /** Why the last call was made, for the review file. */
    var lastWhy = ""
        private set

    private fun fire(t: Long, r: Result, why: String): Result {
        lastWhy = why
        confirming = false
        lastAbove = null
        candidate = null
        reacquiring = false
        newShot()
        phase = Phase.COOLDOWN
        cooldownUntil = t + cfg.cooldownMs
        return r
    }

    private fun insideRing(x: Float, rim: Box): Boolean {
        val tol = cfg.ringTolerance * rim.w
        return x > rim.x - tol && x < rim.right + tol
    }

    /** Ball should look about ball-sized next to the rim; rejects a ball near the camera overlapping the rim. */
    private fun depthOk(rim: Box): Boolean {
        val ws = trail.toList().takeLast(4).map { it.w }.sorted()
        if (ws.isEmpty()) return true
        val median = ws[ws.size / 2]
        val ratio = median / (cfg.ballToRim * rim.w)
        return ratio in cfg.depthMin..cfg.depthMax
    }

    private fun xAtLine(x0: Float, y0: Float, x1: Float, y1: Float, lineY: Float): Float {
        if (y1 == y0) return x1
        val f = ((lineY - y0) / (y1 - y0)).coerceIn(0f, 1f)
        return x0 + (x1 - x0) * f
    }

    /**
     * Which detection is "our" ball this frame.
     * While a shot is in flight, stick to the tracked ball. Otherwise, any ball that
     * appears above the rim takes over (a new shot), even if we were following
     * another ball rolling around on the floor.
     */
    private fun pick(t: Long, dets: List<Detection>, rim: Box?): Detection? {
        if (dets.isEmpty()) return null
        val a = lastAbove
        if (reacquiring && rim != null && a != null) {
            // The ball vanished near the rim: look where it should come out (under the net), not where
            // the last sighting was heading.
            val u = rim.w
            val ringY = rim.y + cfg.ringLineFrac * rim.h
            val plausible = dets.filter { it.cy > ringY && abs(it.cx - a.cx()) <= cfg.reacquireWidth * u }
            val exit = plausible.minByOrNull { (it.cx - rim.cx) * (it.cx - rim.cx) + (it.cy - rim.bottom) * (it.cy - rim.bottom) }
            if (exit != null) return exit
        }
        val tracked = gated(t, dets, rim)
        if (rim != null && (phase == Phase.IDLE || phase == Phase.COOLDOWN)) {
            val u = rim.w
            fun inArmZone(d: Detection) =
                d.cy < rim.y - cfg.pickAbove * u && abs(d.cx - rim.cx) < cfg.armHalfWidth * u
            if (tracked == null || !inArmZone(tracked)) {
                val shot = dets.filter { inArmZone(it) }.maxByOrNull { it.score }
                if (shot != null) {
                    if (tracked != null) trail.clear() // switching balls: old path is irrelevant
                    return shot
                }
            }
        }
        if (tracked != null) return tracked
        val trackLive = trail.lastOrNull()?.let { t - it.t <= cfg.maxGapMs } ?: false
        if (trackLive) return null // our ball is briefly hidden; don't jump to a different one
        return if (rim != null) {
            dets.minByOrNull { (it.cx - rim.cx) * (it.cx - rim.cx) + (it.cy - rim.cy) * (it.cy - rim.cy) }
        } else {
            dets.maxByOrNull { it.score }
        }
    }

    /** Nearest detection to where the tracked ball should be now, if close enough. */
    private fun gated(t: Long, dets: List<Detection>, rim: Box?): Detection? {
        val last = trail.lastOrNull() ?: return null
        if (t - last.t > cfg.maxGapMs) return null
        val prev = if (trail.size >= 2) trail[trail.size - 2] else null
        val dt = max(1L, t - last.t).toFloat()
        var px = last.x
        var py = last.y
        if (prev != null && last.t > prev.t) {
            val span = (last.t - prev.t).toFloat()
            px += (last.x - prev.x) / span * dt
            py += (last.y - prev.y) / span * dt
        }
        val unit = rim?.w ?: (last.w * 2f)
        val gate = unit * cfg.gateRimWidths * min(3f, max(1f, dt / 33f))
        var best: Detection? = null
        var bestD = Float.MAX_VALUE
        for (d in dets) {
            val dx = d.cx - px
            val dy = d.cy - py
            val dist = kotlin.math.sqrt(dx * dx + dy * dy)
            if (dist < bestD) { bestD = dist; best = d }
        }
        return if (bestD <= gate) best else null
    }
}
