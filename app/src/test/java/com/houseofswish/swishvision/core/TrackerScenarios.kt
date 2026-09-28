package com.houseofswish.swishvision.core

import kotlin.random.Random

/**
 * Simulated shots against a rim box, run through the real ShotTracker.
 * Units: the rim is 100 px wide (U = 100), so 1 m ~ 219 px; 30 fps unless noted.
 * Shared by the JUnit test (CI) and a plain main() runner (local).
 */
object TrackerScenarios {
    val RIM = Box(900f, 400f, 100f, 80f) // ring line at y = 420, box bottom at 480
    private const val BALL_W = 52f

    class Sim(val fps: Int = 30, seed: Int = 7) {
        val tracker = ShotTracker()
        var t = 1_000L
        val calls = ArrayList<Result>()
        private val rnd = Random(seed)
        private fun jitter() = (rnd.nextFloat() - 0.5f) * 4f

        fun frame(ball: Pair<Float, Float>?, w: Float = BALL_W, extra: List<Detection> = emptyList()) {
            val dets = ArrayList<Detection>(extra)
            if (ball != null) dets += Detection(ball.first + jitter(), ball.second + jitter(), w, w, 0.8f)
            tracker.update(t, dets, RIM)?.let { calls += it }
            t += 1000L / fps
        }

        fun run(points: List<Pair<Float, Float>>, w: Float = BALL_W, extra: List<Detection> = emptyList()) =
            points.forEach { frame(it, w, extra) }

        fun nothing(ms: Long) { repeat((ms * fps / 1000).toInt()) { frame(null) } }
    }

    /** Falling ball: starts 250 px above the ring line and reaches it at [ringX] after ~8 frames. */
    fun descent(ringX: Float, frames: Int = 22, vx: Float = 6f, fps: Int = 30): List<Pair<Float, Float>> {
        val k = 30f / fps // scale per-frame motion for other frame rates
        val g = 2.4f * k * k
        val vy0 = 20f * k
        val vxf = vx * k
        // time (in frames) to fall from y=170 to y=420
        val n = (-vy0 + kotlin.math.sqrt(vy0 * vy0 + 2 * g * 250f)) / g
        val x0 = ringX - vxf * n
        return (0 until frames).map { i -> Pair(x0 + vxf * i, 170f + vy0 * i + 0.5f * g * i * i) }
    }

    /** Straight line from a to b over n frames (for bounces). */
    fun line(a: Pair<Float, Float>, b: Pair<Float, Float>, n: Int) =
        (1..n).map { i -> val f = i / n.toFloat(); Pair(a.first + (b.first - a.first) * f, a.second + (b.second - a.second) * f) }

    data class Scenario(val name: String, val expected: List<Result>, val body: () -> List<Result>)

    val all = listOf(
        Scenario("swish through the middle", listOf(Result.MAKE)) {
            Sim().apply { run(descent(950f)) }.calls
        },
        Scenario("make that vanishes into the net", listOf(Result.MAKE)) {
            Sim().apply { run(descent(955f).take(10)); nothing(900) }.calls
        },
        Scenario("front rim, falls out short", listOf(Result.MISS)) {
            Sim().apply {
                val d = descent(897f).take(10) // just catches the left edge of the rim
                run(d)
                run(line(d.last(), Pair(840f, 395f), 5)) // kicks back out and up a bit
                run(line(Pair(840f, 395f), Pair(800f, 620f), 8)) // then drops outside
            }.calls
        },
        Scenario("airball well left of the rim", listOf(Result.MISS)) {
            Sim().apply { run(descent(790f)) }.calls
        },
        Scenario("rim-out: in, pops up, falls off the right side", listOf(Result.MISS)) {
            Sim().apply {
                val d = descent(965f).take(10)
                run(d)
                run(line(d.last(), Pair(1010f, 360f), 5))
                run(line(Pair(1010f, 360f), Pair(1090f, 640f), 9))
            }.calls
        },
        Scenario("bounces on the rim then drops in (one make)", listOf(Result.MAKE)) {
            Sim().apply {
                val d = descent(910f).take(10)
                run(d)
                run(line(d.last(), Pair(945f, 370f), 4))
                run(line(Pair(945f, 370f), Pair(950f, 600f), 8))
            }.calls
        },
        Scenario("dribbling under the rim never counts", emptyList()) {
            Sim().apply {
                repeat(6) {
                    run(line(Pair(700f, 900f), Pair(720f, 700f), 5))
                    run(line(Pair(720f, 700f), Pair(700f, 900f), 5))
                }
            }.calls
        },
        Scenario("make then miss, two seconds apart", listOf(Result.MAKE, Result.MISS)) {
            Sim().apply {
                run(descent(950f)); nothing(1500)
                run(descent(780f))
            }.calls
        },
        Scenario("fast drop at 15 fps skips frames", listOf(Result.MAKE)) {
            Sim(fps = 15).apply { run(descent(950f, frames = 12, fps = 15)) }.calls
        },
        Scenario("second ball bouncing elsewhere is ignored", listOf(Result.MAKE)) {
            Sim().apply {
                val other = listOf(Detection(300f, 750f, 50f, 50f, 0.9f))
                run(descent(950f), extra = other)
            }.calls
        },
        Scenario("ball close to the camera crossing the rim is ignored", emptyList()) {
            Sim().apply { run(descent(950f), w = 150f) }.calls
        },
        Scenario("layup: up past the rim, then in", listOf(Result.MAKE)) {
            Sim().apply {
                run(line(Pair(880f, 560f), Pair(935f, 330f), 7)) // rising beside the rim
                run(line(Pair(935f, 330f), Pair(950f, 600f), 9))
            }.calls
        },
        Scenario("lost at the top of a high arc, comes back down and in", listOf(Result.MAKE)) {
            Sim().apply {
                run(line(Pair(800f, 300f), Pair(860f, 60f), 5)) // going up, above the rim
                nothing(600) // out of the crop at the top
                run(descent(950f).drop(3))
            }.calls
        },
        Scenario("hits the side of the rim and flies out of view", listOf(Result.MISS)) {
            Sim().apply {
                val d = descent(1003f).take(9)
                run(d)
                run(line(d.last(), Pair(1060f, 440f), 3))
                nothing(1200)
            }.calls
        },
        Scenario("ball resting under the hoop, then a shot goes in", listOf(Result.MAKE)) {
            Sim().apply {
                val resting = listOf(Detection(960f, 900f, 55f, 55f, 0.95f))
                repeat(20) { frame(null, extra = resting) } // tracker locks onto the resting ball
                run(descent(950f), extra = resting)
            }.calls
        },
        Scenario("patchy detection (every 3rd frame missing): make, then miss", listOf(Result.MAKE, Result.MISS)) {
            Sim().apply {
                descent(950f).forEachIndexed { i, p -> frame(if (i % 3 == 2) null else p) }
                nothing(1500)
                descent(790f).forEachIndexed { i, p -> frame(if (i % 3 == 1) null else p) }
            }.calls
        },
        Scenario("ten makes in a row", List(10) { Result.MAKE }) {
            Sim().apply { repeat(10) { run(descent(940f + it * 2)); nothing(1300) } }.calls
        },
        Scenario("net shake after a make does not double count", listOf(Result.MAKE)) {
            Sim().apply {
                run(descent(950f))
                // ball dropped, bounces on floor, a kid holds it overhead near the rim briefly
                run(line(Pair(950f, 900f), Pair(950f, 700f), 6))
                nothing(300)
            }.calls
        },
    )

    /** Returns failures as text; empty when everything passes. */
    fun runAll(verbose: Boolean = false): List<String> {
        val failures = ArrayList<String>()
        for (s in all) {
            val got = s.body()
            val ok = got == s.expected
            if (verbose) println((if (ok) "PASS  " else "FAIL  ") + s.name + "  expected=" + s.expected + " got=" + got)
            if (!ok) failures += "${s.name}: expected ${s.expected}, got $got"
        }
        return failures
    }
}
