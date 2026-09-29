package com.houseofswish.swishvision.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Finds the hoop automatically from the model's "Basketball Hoop" detections,
 * and keeps the rim box on it if the phone shifts a little.
 *
 * The model's hoop box covers the rim plus the whole net (rim at the very top).
 * The tracker's rim box is "the ring and the top of the net" with the ring 25% down,
 * so [toRimBox] converts one into the other.
 */
class HoopFinder(
    private val needFrames: Int = 8,     // same hoop seen this many frames in a row
    private val minScore: Float = 0.5f,
) {
    private var ema: Box? = null
    private var streak = 0

    fun reset() {
        ema = null
        streak = 0
    }

    /** While no rim is set. Returns a rim box once the hoop has been seen steadily. */
    fun find(hoops: List<Detection>): Box? {
        val h = hoops.maxByOrNull { it.score }?.takeIf { it.score >= minScore }
        if (h == null) {
            streak = 0
            return null
        }
        val b = toRimBox(h)
        val e = ema
        if (e != null && iou(e, b) > 0.5f) {
            ema = blend(e, b, 0.3f)
            streak++
        } else {
            ema = b
            streak = 1
        }
        return if (streak >= needFrames) ema else null
    }

    /**
     * While tracking with an auto-found rim: if the hoop has drifted (phone nudged),
     * returns a gently corrected rim box; null when no change is needed.
     */
    fun follow(current: Box, hoops: List<Detection>): Box? {
        val h = hoops.maxByOrNull { it.score }?.takeIf { it.score >= minScore } ?: return null
        val b = toRimBox(h)
        if (iou(current, b) < 0.3f) return null // probably not the same hoop
        val moved = max(abs(b.x - current.x), abs(b.y - current.y))
        if (moved < 0.04f * current.w) return null // small jitter: leave it alone
        return blend(current, b, 0.2f)
    }

    companion object {
        fun toRimBox(h: Detection): Box {
            val w = h.w
            val left = h.cx - w / 2f
            val top = (h.cy - h.h / 2f) - 0.05f * w
            return Box(left, top, w, 0.6f * w)
        }

        fun iou(a: Box, b: Box): Float {
            val l = max(a.x, b.x)
            val t = max(a.y, b.y)
            val r = min(a.right, b.right)
            val bt = min(a.bottom, b.bottom)
            val inter = max(0f, r - l) * max(0f, bt - t)
            val union = a.w * a.h + b.w * b.h - inter
            return if (union <= 0f) 0f else inter / union
        }

        fun blend(a: Box, b: Box, k: Float) = Box(
            a.x + (b.x - a.x) * k, a.y + (b.y - a.y) * k,
            a.w + (b.w - a.w) * k, a.h + (b.h - a.h) * k,
        )
    }
}
