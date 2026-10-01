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

        // Hoop finder: needs a steady hoop, ignores one-off detections, follows small shifts
        val hf = HoopFinder()
        val hoop = Detection(360f, 164f, 40f, 48f, 0.85f) // from the real test video
        var found: Box? = null
        repeat(7) { found = hf.find(listOf(hoop)) }
        check(found == null, "hoop found too early", f)
        found = hf.find(listOf(hoop))
        check(found != null, "hoop not found after 8 steady frames", f)
        found?.let { b ->
            check(kotlin.math.abs(b.w - 40f) < 0.5f && kotlin.math.abs(b.h - 24f) < 0.5f, "rim box size $b", f)
            // ring line (25% down the rim box) should sit near the top of the hoop box (rim)
            val ringY = b.y + 0.25f * b.h
            check(ringY > 140f && ringY < 148f, "ring line at $ringY (hoop top 140)", f)
            check(hf.follow(b, listOf(hoop)) == null, "follow moved a steady rim", f)
            val shifted = Detection(372f, 170f, 40f, 48f, 0.85f)
            val moved = hf.follow(b, listOf(shifted))
            check(moved != null && moved.x > b.x && moved.x < b.x + 12f, "follow didn't nudge: $moved", f)
            check(hf.follow(b, listOf(Detection(700f, 400f, 40f, 48f, 0.9f))) == null, "followed a different hoop", f)
        }
        val hf2 = HoopFinder()
        repeat(20) { i -> hf2.find(if (i % 2 == 0) listOf(hoop) else emptyList()) }
        check(hf2.find(emptyList()) == null, "flickering hoop accepted", f)

        // Announcer
        val quiet = AnnouncerScript(kotlin.random.Random(1), callChance = 0.0, threeChance = 0.0)
        check(quiet.forMake(1, 1, false) == AnnouncerScript.Call("1!"), "plain make: ${quiet.forMake(1, 1, false)}", f)
        check(quiet.forMake(7, 3, false) == AnnouncerScript.Call("7!", clip = "streak_heating_up"), "heating up", f)
        check(quiet.forMake(8, 4, true) == AnnouncerScript.Call("8!", clip = "streak_on_fire"), "on fire beats a three", f)
        check(quiet.forMake(12, 5, false).clip == "bonus_1", "bonus", f)
        check(quiet.forMake(20, 10, false).clip == "bonus_2", "double bonus", f)
        check(quiet.forMake(30, 15, false).clip == "bonus_3", "triple bonus", f)
        check(quiet.forMake(40, 20, false).clip == "bonus_4", "quadruple bonus", f)
        check(quiet.forMake(50, 25, false).clip == "bonus_5", "quintuple bonus", f)
        check(quiet.forMake(60, 30, false) == AnnouncerScript.Call("60!", say = "Sextuple bonus!"), "past the clips: phone voice", f)
        check(AnnouncerScript.bonus(4) == "Quadruple bonus!" && AnnouncerScript.bonus(11) == "Bonus times 11!", "bonus words", f)
        check(quiet.forMake(9, 6, true).clip == null, "call with zero chance", f)

        // Every clip the script can ask for has words for the phone voice to fall back on.
        val all = AnnouncerScript.CALLS + AnnouncerScript.THREES + AnnouncerScript.HOT + AnnouncerScript.BONUS +
            listOf(AnnouncerScript.HEATING_UP, AnnouncerScript.ON_FIRE, AnnouncerScript.NOT_SO_FAST,
                AnnouncerScript.YOU_GOT_THIS, AnnouncerScript.START)
        check(all.size == 35 && all.toSet().size == 35, "35 different clips: ${all.size}", f)
        check(all.all { it in AnnouncerScript.TEXT }, "clip without words: ${all.filter { it !in AnnouncerScript.TEXT }}", f)

        // Every call plays once before any repeats, and never the same one twice in a row.
        val loud = AnnouncerScript(kotlin.random.Random(7), callChance = 1.0)
        val heard = (1..60).map { loud.forMake(it, 1, false).clip!! }
        check(heard.take(20).toSet() == AnnouncerScript.CALLS.toSet(), "first 20 calls not all different", f)
        check(heard.drop(20).take(20).toSet() == AnnouncerScript.CALLS.toSet(), "second round not all different", f)
        check(heard.zipWithNext().none { (x, y) -> x == y }, "same call twice in a row", f)
        val threes = (1..200).mapNotNull { AnnouncerScript(kotlin.random.Random(it)).forMake(it, 1, true).clip }
        check(threes.count { it.startsWith("three_") } > 80, "threes don't lean downtown: ${threes.count { it.startsWith("three_") }}", f)
        check((1..200).none { AnnouncerScript(kotlin.random.Random(it)).forMake(it, 1, false).clip?.startsWith("three_") == true },
            "downtown call on a two", f)
        val hot = (1..300).mapNotNull { AnnouncerScript(kotlin.random.Random(it), callChance = 1.0).forMake(it, 7, false).clip }
        check(hot.any { it.startsWith("streak_") && "fuego" in it }, "no en fuego on a long streak", f)
        check((1..300).none { "fuego" in (AnnouncerScript(kotlin.random.Random(it), callChance = 1.0).forMake(it, 2, false).clip ?: "") },
            "en fuego without a streak", f)

        // Misses
        check(quiet.forMiss(1, 4) == "miss_not_so_fast", "streak ended", f)
        check(quiet.forMiss(1, 2) == null, "short streak ended: say nothing", f)
        check(quiet.forMiss(3, 0) == "miss_you_got_this" && quiet.forMiss(6, 0) == "miss_you_got_this", "you got this", f)
        check(quiet.forMiss(2, 0) == null && quiet.forMiss(4, 0) == null, "misses: say nothing", f)
        check(AnnouncerScript.firstName("  ") == null && AnnouncerScript.firstName("JMS") == "JMS", "first name", f)

        // Session counters the announcer uses
        val ms = Session(0)
        ms.add(1, Result.MAKE, ShotType.FT, Method.AUTO); ms.add(2, Result.MISS, ShotType.FT, Method.AUTO)
        ms.add(3, Result.MISS, ShotType.FT, Method.MANUAL)
        check(ms.missesInRow == 2 && ms.currentStreak == 0, "misses in a row: ${ms.missesInRow}", f)
        return f
    }
}
