package com.houseofswish.swishvision.core

import kotlin.math.max
import kotlin.math.min

/** Axis-aligned box in camera-frame pixels (x,y = top-left). */
data class Box(val x: Float, val y: Float, val w: Float, val h: Float) {
    val right get() = x + w
    val bottom get() = y + h
    val cx get() = x + w / 2f
    val cy get() = y + h / 2f
}

/** One ball detection, centre-based, in camera-frame pixels. */
data class Detection(val cx: Float, val cy: Float, val w: Float, val h: Float, val score: Float) {
    fun iou(o: Detection): Float {
        val l = max(cx - w / 2, o.cx - o.w / 2)
        val t = max(cy - h / 2, o.cy - o.h / 2)
        val r = min(cx + w / 2, o.cx + o.w / 2)
        val b = min(cy + h / 2, o.cy + o.h / 2)
        val inter = max(0f, r - l) * max(0f, b - t)
        val union = w * h + o.w * o.h - inter
        return if (union <= 0f) 0f else inter / union
    }
}

/**
 * Region of the camera frame fed to the model, and how it was scaled.
 * The model sees a square [inputSize] x [inputSize] image: the crop is scaled
 * uniformly by [scale] and placed at the top-left; the rest is padding.
 */
data class Roi(val x: Int, val y: Int, val w: Int, val h: Int, val inputSize: Int) {
    val scale: Float get() = inputSize.toFloat() / max(w, h).toFloat()

    /** Model-input pixels -> camera-frame pixels. */
    fun toFrame(d: Detection): Detection =
        Detection(x + d.cx / scale, y + d.cy / scale, d.w / scale, d.h / scale, d.score)

    companion object {
        /** Whole frame, letterboxed. */
        fun full(frameW: Int, frameH: Int, inputSize: Int) = Roi(0, 0, frameW, frameH, inputSize)

        /**
         * Square crop around the rim so the ball is big in the model's eyes.
         * [sizeFactor] rim-widths wide; the rim sits a little above centre
         * because the ball's approach (from above) matters most.
         */
        fun aroundRim(rim: Box, frameW: Int, frameH: Int, inputSize: Int, sizeFactor: Float = 6f): Roi {
            var side = max((rim.w * sizeFactor).toInt(), inputSize / 2)
            side = side.coerceAtMost(min(frameW, frameH))
            var left = (rim.cx - side / 2f).toInt()
            var top = (rim.cy - side * 0.45f).toInt()
            left = left.coerceIn(0, frameW - side)
            top = top.coerceIn(0, frameH - side)
            return Roi(left, top, side, side, inputSize)
        }
    }
}
