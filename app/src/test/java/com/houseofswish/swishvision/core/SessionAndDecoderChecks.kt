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
        val a = AnnouncerScript(kotlin.random.Random(1))
        check(a.forMake(7, 3, false) == AnnouncerScript.Call("7!", clip = "streak_heating_up"), "heating up", f)
        check(a.forMake(8, 4, true) == AnnouncerScript.Call("8!", clip = "streak_on_fire"), "on fire beats a three", f)
        check(a.forMake(12, 5, false).clip == "bonus_1", "bonus", f)
        check(a.forMake(20, 10, false).clip == "bonus_2", "double bonus", f)
        check(a.forMake(30, 15, false).clip == "bonus_3", "triple bonus", f)
        check(a.forMake(40, 20, false).clip == "bonus_4", "quadruple bonus", f)
        check(a.forMake(50, 25, false).clip == "bonus_5", "quintuple bonus", f)
        check(a.forMake(60, 30, false) == AnnouncerScript.Call("60!", say = "Sextuple bonus!"), "past the clips: phone voice", f)
        check(AnnouncerScript.bonus(4) == "Quadruple bonus!" && AnnouncerScript.bonus(11) == "Bonus times 11!", "bonus words", f)

        // Every clip the script can ask for has words for the phone voice to fall back on.
        val all = AnnouncerScript.CALLS + AnnouncerScript.THREES + AnnouncerScript.HOT + AnnouncerScript.BONUS +
            AnnouncerScript.MISSES + listOf(AnnouncerScript.HEATING_UP, AnnouncerScript.ON_FIRE,
                AnnouncerScript.NOT_SO_FAST, AnnouncerScript.YOU_GOT_THIS, AnnouncerScript.START, AnnouncerScript.SIX_SEVEN)
        check(all.toSet().size == all.size, "a clip is listed twice", f)
        check(all.all { it in AnnouncerScript.TEXT }, "clip without words: ${all.filter { it !in AnnouncerScript.TEXT }}", f)

        // A call after every make; every call once before any repeats; never the same one back to back.
        val r = AnnouncerScript(kotlin.random.Random(7))
        val nCalls = AnnouncerScript.CALLS.size
        val heard = (1..nCalls * 3).map { r.forMake(it + 100, if (it % 2 == 0) 1 else 2, false).clip } // (+100: stay clear of make 67)
        check(heard.all { it != null && it in AnnouncerScript.CALLS }, "make without a call, or a wrong kind: $heard", f)
        check(heard.take(nCalls).toSet() == AnnouncerScript.CALLS.toSet(), "first round of calls not all different", f)
        check(heard.drop(nCalls).take(nCalls).toSet() == AnnouncerScript.CALLS.toSet(), "second round not all different", f)
        check(heard.zipWithNext().none { (x, y) -> x == y }, "same call twice in a row", f)

        // Mixed shooting: threes, twos and long streaks. Nothing repeats until everything that fits has played,
        // downtown calls only on threes, en fuego only on long streaks.
        for (seed in 1..40) {
            val m = AnnouncerScript(kotlin.random.Random(seed))
            val rng = kotlin.random.Random(seed + 1000)
            var streak = 0
            val got = ArrayList<String>()
            repeat(20) { i ->
                streak = if (rng.nextDouble() < 0.25) 1 else streak + 1
                if (streak in 3..5 || streak % 5 == 0) streak = 6 // keep clear of the fixed milestones
                val three = rng.nextDouble() < 0.4
                val c = m.forMake(i + 1, streak, three).clip!!
                if (c.startsWith("three_")) check(three, "downtown call on a two (seed $seed)", f)
                if ("fuego" in c) check(streak >= 6, "en fuego without a streak (seed $seed)", f)
                got += c
            }
            check(got.size == got.toSet().size, "a call repeated within 20 makes (seed $seed): $got", f)
        }
        val leanThree = AnnouncerScript(kotlin.random.Random(3))
        check(AnnouncerScript.THREES.contains(leanThree.forMake(1, 1, true).clip) ||
            AnnouncerScript.THREES.contains(leanThree.forMake(2, 1, true).clip), "threes don't lean downtown", f)
        val fuego = (1..50).count { "fuego" in (AnnouncerScript(kotlin.random.Random(it)).forMake(9, 7, false).clip ?: "") }
        check(fuego in 20..45, "long streaks don't lean en fuego: $fuego/50", f)

        // "Six seven" plays on the 67th make, whatever the streak, and never anywhere else.
        val sx = AnnouncerScript(kotlin.random.Random(9))
        val sixSevens = (1..300).map { n -> n to sx.forMake(n, if (n % 5 == 0) 5 else n % 4 + 1, n % 3 == 0).clip }.filter { it.second == AnnouncerScript.SIX_SEVEN }
        check(sixSevens.map { it.first } == listOf(67), "six seven played at ${sixSevens.map { it.first }}", f)
        check(AnnouncerScript(kotlin.random.Random(1)).forMake(67, 10, false).clip == AnnouncerScript.SIX_SEVEN, "six seven beats a bonus", f)
        // Misses: a comment after every miss.
        check(a.forMiss(1, 4).clip == "miss_not_so_fast", "streak ended", f)
        check(a.forMiss(3, 0).clip == "miss_you_got_this" && a.forMiss(6, 0).clip == "miss_you_got_this", "you got this", f)
        val misses = AnnouncerScript(kotlin.random.Random(5))
        val nMiss = AnnouncerScript.MISSES.size
        val mh = (1..nMiss * 2).map { misses.forMiss(if (it % 3 == 0) 1 else 2, 0).clip!! }
        check(mh.take(nMiss).toSet() == AnnouncerScript.MISSES.toSet(), "first round of miss comments not all different: $mh", f)
        check(mh.zipWithNext().none { (x, y) -> x == y }, "same miss comment twice in a row", f)
        check(AnnouncerScript.firstName("  ") == null && AnnouncerScript.firstName("JMS") == "JMS", "first name", f)

        // Shot spots, from a real driveway session (1920 x 1080, camera beside the court)
        val sRim = Box(1250f, 270f, 110f, 66f)
        fun person(cx: Float, feet: Float, h: Float) = Detection(cx, feet - h / 2f, h * 0.4f, h, 0.8f)
        val sf = SpotFinder()
        fun at(t: Long, p: Detection): SpotFinder.Spot? { sf.add(t, listOf(p), 1920, 1080); return sf.spotAt(t, sRim) }
        check(at(1_000, person(1041f, 708f, 358f))?.type == ShotType.LAYUP, "layup spot", f)
        check(at(5_000, person(574f, 756f, 368f))?.type == ShotType.MID, "mid-range spot", f)
        check(at(9_000, person(300f, 690f, 250f))?.type == ShotType.THREE, "three spot", f)
        val ftBefore = at(13_000, person(203f, 880f, 498f))
        check(ftBefore?.type == ShotType.MID && !sf.calibrated, "free throw before the spot is set: mid-range", f)
        check(sf.setFreeThrowSpot(13_200, sRim) && sf.scale in 0.85f..1.0f, "set free-throw spot (scale ${sf.scale})", f)
        val ftShot = at(17_000, person(221f, 873f, 487f))
        check(ftShot?.type == ShotType.FT, "free throw after the spot is set", f)
        check(ftShot?.courtY != null && ftShot.courtY in 12f..15.5f && kotlin.math.abs(ftShot.courtX!!) < 1.5f, "free throw on the chart: straight out ~13.75 ft ($ftShot)", f)
        check(at(21_000, person(574f, 756f, 368f))?.type == ShotType.MID, "mid-range away from the line stays mid", f)
        check(at(25_000, person(926f, 1075f, 752f)) == null, "feet cut off: no guess", f)
        check(at(40_000, person(574f, 756f, 368f)).let { it != null && it.t == 40_000L }, "uses the latest sighting", f)
        val stale = SpotFinder(); stale.add(0, listOf(person(574f, 756f, 368f)), 1920, 1080)
        check(stale.spotAt(10_000, sRim) == null, "no guess from a sighting long before the shot", f)
        val s2 = Session(0); s2.add(1, Result.MAKE, ShotType.MID, Method.AUTO); s2.retypeLast(ShotType.FT)
        check(s2.byType()[ShotType.FT] == TypeLine(1, 1) && s2.byType()[ShotType.MID] == TypeLine(0, 0), "retype last shot", f)

        // Shot chart zones (x = shooter's right, y = out from the rim, feet)
        check(ShotChart.zoneOf(0f, 3f) == ShotChart.Zone.RIM, "zone: at the rim", f)
        check(ShotChart.zoneOf(0f, 12f) == ShotChart.Zone.MID_CENTER, "zone: middle", f)
        check(ShotChart.zoneOf(-8f, 8f) == ShotChart.Zone.MID_LEFT && ShotChart.zoneOf(8f, 8f) == ShotChart.Zone.MID_RIGHT, "zone: elbows", f)
        check(ShotChart.zoneOf(14f, 1f) == ShotChart.Zone.MID_RIGHT_BASE, "zone: right baseline", f)
        check(ShotChart.zoneOf(-19f, 2f) == ShotChart.Zone.THREE_LEFT_CORNER && ShotChart.zoneOf(0f, 20f) == ShotChart.Zone.THREE_TOP, "zone: threes", f)
        val cs = Session(0)
        cs.add(1, Result.MAKE, ShotType.MID, Method.AUTO).apply { courtX = 0f; courtY = 12f }
        cs.add(2, Result.MISS, ShotType.MID, Method.AUTO).apply { courtX = 1f; courtY = 11f }
        cs.add(3, Result.MAKE, ShotType.FT, Method.AUTO).apply { courtX = 0f; courtY = 13.7f }
        cs.add(4, Result.MAKE, ShotType.LAYUP, Method.MANUAL)
        check(ShotChart.zoneLines(cs.shots) == mapOf(ShotChart.Zone.MID_CENTER to TypeLine(1, 2)), "zone lines (FT and unplaced left out): ${ShotChart.zoneLines(cs.shots)}", f)
        val cj = cs.toJson(10L, 0f, "m")
        check(cj.contains("\"zones\": {\"mid_center\": {\"attempted\": 2, \"made\": 1}}") && cj.contains("\"x\": 0.0, \"y\": 12.0"), "json zones / positions: $cj", f)
        val rt = Session(0); rt.add(1, Result.MAKE, ShotType.MID, Method.AUTO).apply { courtX = 0f; courtY = 12f }; rt.retypeLast(ShotType.THREE)
        check(rt.shots[0].courtX == null, "retyped shot comes off the chart", f)

        // Session counters the announcer uses
        val ms = Session(0)
        ms.add(1, Result.MAKE, ShotType.FT, Method.AUTO); ms.add(2, Result.MISS, ShotType.FT, Method.AUTO)
        ms.add(3, Result.MISS, ShotType.FT, Method.MANUAL)
        check(ms.missesInRow == 2 && ms.currentStreak == 0, "misses in a row: ${ms.missesInRow}", f)
        return f
    }
}
