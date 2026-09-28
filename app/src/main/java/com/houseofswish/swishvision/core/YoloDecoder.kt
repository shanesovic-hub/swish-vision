package com.houseofswish.swishvision.core

/**
 * Decodes a YOLO11 detection output laid out as [1, 4 + numClasses, numAnchors]
 * (the Ultralytics LiteRT export). Rows 0-3 are cx, cy, w, h; the rest are
 * per-class scores (already sigmoid-activated). Keeps one class only.
 */
object YoloDecoder {
    const val COCO_SPORTS_BALL = 32

    fun decode(
        out: FloatArray,
        numAnchors: Int,
        numClasses: Int,
        classId: Int,
        confThreshold: Float,
        inputSize: Int,
        normalizedBoxes: Boolean,
        iouThreshold: Float = 0.45f,
        maxResults: Int = 10,
    ): List<Detection> {
        require(out.size >= (4 + numClasses) * numAnchors) { "output too small: ${out.size}" }
        val scoreRow = (4 + classId) * numAnchors
        val k = if (normalizedBoxes) inputSize.toFloat() else 1f
        val found = ArrayList<Detection>()
        for (a in 0 until numAnchors) {
            val s = out[scoreRow + a]
            if (s < confThreshold) continue
            found += Detection(
                cx = out[a] * k,
                cy = out[numAnchors + a] * k,
                w = out[2 * numAnchors + a] * k,
                h = out[3 * numAnchors + a] * k,
                score = s,
            )
        }
        return nms(found, iouThreshold, maxResults)
    }

    fun nms(dets: List<Detection>, iouThreshold: Float, maxResults: Int): List<Detection> {
        val sorted = dets.sortedByDescending { it.score }
        val kept = ArrayList<Detection>()
        for (d in sorted) {
            if (kept.size >= maxResults) break
            if (kept.none { it.iou(d) > iouThreshold }) kept += d
        }
        return kept
    }
}
