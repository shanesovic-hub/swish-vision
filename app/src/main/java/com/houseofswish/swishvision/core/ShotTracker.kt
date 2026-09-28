package com.houseofswish.swishvision.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Result { MAKE, MISS }

enum class Phase { IDLE, ARMED, PENDING_MAKE, COOLDOWN }

data class TrackPoint(val t: Long, val x: Float, val y: Float, val w: Float)

/**
 * All distances are in rim widths (U), so the logic works at any camera distance.
 * The rim box is what the user draws: snug around the ring and the top of the net.
 */
data class TrackerConfig(
    val ringLineFrac: Float = 0.25f,   // the ring sits this far down the drawn box
    val armAbove: Float = 0.10f,       // ball must be this far above the box top to arm
    val armHalfWidth: Float = 3.0f,    // ...and within this many U of the rim centre, sideways
    val ringTolerance: Float = 0.10f,  // extra width either side of the box still counted "inside"
    val dropMargin: Float = 0.15f,     // below box bottom by this much = the ball has come down
    val confirmSlack: Float = 0.30f,   // x slack when confirming a make below the net
    val popUp: Float = 0.30f,          // pending make cancelled if ball pops back above ring by this
    val rollOff: Float = 0.60f,        // pending make cancelled if ball drifts this far outside the rim
    val pendingLostMs: Long = 600,     // ball vanished into the net -> make
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
    val depthMax: Float = 1.90f,
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

    fun reset() {
        phase = Phase.IDLE
        trail.clear()
        lastBall = null
        armedAt = 0L; pendingAt = 0L; cooldownUntil = 0L
        lastSeenAt = Long.MIN_VALUE / 2
    }

    /** Feed one analysed frame. Returns a call when a shot is decided. */
    fun update(t: Long, detections: List<Detection>, rim: Box?): Result? {
        val ball = pick(t, detections, rim)
        lastBall = ball
        if (ball != null) {
            val prev = trail.lastOrNull()
            if (prev != null && t - prev.t > cfg.maxGapMs) trail.clear()
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

        when (phase) {
            Phase.IDLE -> {
                if (y < rim.y - cfg.armAbove * u && abs(x - rim.cx) < cfg.armHalfWidth * u && depthOk(rim)) {
                    phase = Phase.ARMED
                    armedAt = t
                }
            }
            Phase.ARMED -> {
                if (p0 != null && p0.y <= ringY && y > ringY) {
                    val xc = xAtLine(p0.x, p0.y, x, y, ringY)
                    if (insideRing(xc, rim)) {
                        if (!depthOk(rim)) { // wrong size for a ball at the rim: not our shot
                            phase = Phase.IDLE
                            return null
                        }
                        if (y > rim.bottom) return fire(t, Result.MAKE) // fell straight through between frames
                        phase = Phase.PENDING_MAKE
                        pendingAt = t
                        return null
                    }
                }
                if (y > rim.bottom + cfg.dropMargin * u) return fire(t, Result.MISS)
                if (y < rim.y - cfg.armAbove * u) armedAt = t // still hanging above the rim: stay armed
                if (t - armedAt > cfg.armedTimeoutMs) phase = Phase.IDLE
            }
            Phase.PENDING_MAKE -> {
                val dx = abs(x - rim.cx)
                if (y > rim.bottom && dx < (0.5f + cfg.confirmSlack) * u) return fire(t, Result.MAKE)
                if (y < ringY - cfg.popUp * u || dx > (0.5f + cfg.rollOff) * u) {
                    phase = Phase.ARMED
                    armedAt = t
                    return null
                }
                if (y > rim.bottom + cfg.dropMargin * u) return fire(t, Result.MISS)
                if (t - pendingAt > cfg.pendingMaxMs) return fire(t, Result.MAKE)
            }
            Phase.COOLDOWN -> Unit
        }
        return null
    }

    private fun onLost(t: Long, rim: Box): Result? {
        val gap = t - lastSeenAt
        val u = rim.w
        when (phase) {
            Phase.PENDING_MAKE -> if (gap > cfg.pendingLostMs) return fire(t, Result.MAKE)
            Phase.ARMED -> {
                val last = trail.lastOrNull()
                if (last != null && gap > cfg.pendingLostMs) {
                    val prev = if (trail.size >= 2) trail[trail.size - 2] else null
                    val descending = prev != null && last.y > prev.y
                    val overOpening = last.x > rim.x && last.x < rim.right &&
                        last.y > rim.y - 0.5f * u && last.y < rim.bottom
                    // Vanished right over the opening while falling: it went into the net.
                    if (descending && overOpening) return fire(t, Result.MAKE)
                    val nearRimLevel = last.y > rim.y - 0.5f * u && abs(last.x - rim.cx) < 2f * u
                    if (gap > cfg.armedLostMs && nearRimLevel) return fire(t, Result.MISS)
                }
                if (t - armedAt > cfg.armedTimeoutMs) phase = Phase.IDLE
            }
            else -> Unit
        }
        return null
    }

    private fun fire(t: Long, r: Result): Result {
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
        val tracked = gated(t, dets, rim)
        if (rim != null && (phase == Phase.IDLE || phase == Phase.COOLDOWN)) {
            val u = rim.w
            fun inArmZone(d: Detection) =
                d.cy < rim.y - cfg.armAbove * u && abs(d.cx - rim.cx) < cfg.armHalfWidth * u
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
