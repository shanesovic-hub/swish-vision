package com.houseofswish.swishvision.core

/** Plain checks shared by JUnit (CI) and the local runner. */
object SessionAndDecoderChecks {
    private fun check(cond: Boolean, msg: String, out: MutableList<String>) { if (!cond) out += msg }

    fun runAll(): List<String> {
        val f = ArrayList<String>()

        // Session stats and the camera report card
        val s = Session(0L)
        s.add(1, Result.MAKE, ShotType.FT, Method.AUTO)
        s.add(2, Result.MAKE, ShotType.FT, Method.AUTO)
        s.add(3, Result.MISS, ShotType.FT, Method.AUTO)
        s.flipLast() // it was actually a make
        s.add(4, Result.MAKE, ShotType.THREE, Method.MANUAL) // camera never saw it
        s.add(5, Result.MISS, ShotType.THREE, Method.AUTO)
        s.add(6, Result.MAKE, ShotType.THREE, Method.AUTO)
        s.undo() // phantom: nothing happened
        check(s.attempts == 5, "attempts ${s.attempts}", f)
        check(s.makes == 4, "makes ${s.makes}", f)
        check(s.bestStreak == 4, "best streak ${s.bestStreak}", f)
        check(s.currentStreak == 0, "current streak ${s.currentStreak}", f)
        check(s.byType()[ShotType.FT] == TypeLine(3, 3), "FT line ${s.byType()[ShotType.FT]}", f)
        check(s.byType()[ShotType.THREE] == TypeLine(1, 2), "3PT line ${s.byType()[ShotType.THREE]}", f)
        check(s.cameraRight == 3, "camera right ${s.cameraRight}", f)
        check(s.cameraWrong == 3, "camera wrong ${s.cameraWrong} (1 flipped, 1 missed, 1 phantom)", f)
        check(s.cameraAccuracy == 50, "camera accuracy ${s.cameraAccuracy}", f)
        s.flipLast(); s.flipLast() // flipping twice returns to the original
        check(s.cameraWrong == 3, "double flip changed wrong count", f)
        val json = s.toJson(60_000L, 28.5f, "yolo11n")
        check(json.contains("\"ft\": {\"attempted\": 3, \"made\": 3}"), "json shotTypes: $json", f)
        check(json.contains("\"minutes\": 1"), "json minutes", f)

        // Decoder: layout [1, 84, N], normalized boxes
        val n = 5
        val out = FloatArray(84 * n)
        fun put(a: Int, cx: Float, cy: Float, w: Float, h: Float, ball: Float) {
            out[a] = cx; out[n + a] = cy; out[2 * n + a] = w; out[3 * n + a] = h
            out[(4 + YoloDecoder.COCO_SPORTS_BALL) * n + a] = ball
        }
        put(0, 0.5f, 0.5f, 0.1f, 0.1f, 0.90f)
        put(1, 0.51f, 0.5f, 0.1f, 0.1f, 0.70f) // overlaps #0 -> suppressed
        put(2, 0.2f, 0.8f, 0.05f, 0.05f, 0.40f)
        put(3, 0.9f, 0.1f, 0.05f, 0.05f, 0.10f) // below threshold
        out[(4 + 0) * n + 4] = 0.99f // a person, not a ball
        val d = YoloDecoder.decode(out, n, 80, YoloDecoder.COCO_SPORTS_BALL, 0.25f, 640, true)
        check(d.size == 2, "decoder count ${d.size}", f)
        check(d.isNotEmpty() && kotlin.math.abs(d[0].cx - 320f) < 0.01f && kotlin.math.abs(d[0].w - 64f) < 0.01f,
            "decoder scaling $d", f)

        // ROI mapping round trip
        val roi = Roi.aroundRim(Box(900f, 400f, 100f, 80f), 1920, 1080, 640)
        check(roi.w == 600 && roi.h == 600, "roi size ${roi.w}x${roi.h}", f)
        val back = roi.toFrame(Detection(320f, 320f, 64f, 64f, 1f))
        check(kotlin.math.abs(back.cx - (roi.x + 300f)) < 0.01f && kotlin.math.abs(back.w - 60f) < 0.01f, "roi map $back", f)
        val edge = Roi.aroundRim(Box(1850f, 20f, 60f, 50f), 1920, 1080, 640)
        check(edge.x + edge.w <= 1920 && edge.y >= 0, "roi clamped $edge", f)
        return f
    }
}
