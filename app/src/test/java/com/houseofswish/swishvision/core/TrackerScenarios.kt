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

    /**
     * Falling ball: starts 250 px above the ring line and reaches it at [ringX] after ~8 frames.
     * Inside the rim the net slows it down (real makes come out of the net at ~2-6 rim widths/s,
     * a ball falling past the net keeps speeding up).
     */
    fun descent(ringX: Float, frames: Int = 22, vx: Float = 6f, fps: Int = 30): List<Pair<Float, Float>> {
        val k = 30f / fps // scale per-frame motion for other frame rates
        val g = 2.4f * k * k
        val vy0 = 20f * k
        val vxf = vx * k
        // time (in frames) to fall from y=170 to y=420
        val n = (-vy0 + kotlin.math.sqrt(vy0 * vy0 + 2 * g * 250f)) / g
        val x0 = ringX - vxf * n
        val inRim = kotlin.math.abs(ringX - RIM.cx) < RIM.w / 2
        val netSpeed = 13f * k // ~4 rim widths/s
        val out = ArrayList<Pair<Float, Float>>()
        var x = x0
        var y = 170f
        var vy = vy0
        repeat(frames) {
            out += Pair(x, y)
            val inNet = inRim && y > 420f && y < 540f
            if (inNet) vy = kotlin.math.min(vy, netSpeed) else vy += g
            x += if (inNet) vxf * 0.3f else vxf
            y += vy
        }
        return out
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
                run(line(Pair(945f, 370f), Pair(948f, 480f), 5))
                run(line(Pair(948f, 480f), Pair(950f, 600f), 9)) // the net slows it down
            }.calls
        },
        Scenario("side view: drops past the rim at full speed, not slowed by a net (miss)", listOf(Result.MISS)) {
            Sim().apply {
                val d = descent(950f).take(7)                  // comes down over the middle of the rim (on screen)
                run(d)
                run(line(d.last(), Pair(955f, 760f), 11))      // but keeps falling fast: it went past the net, not through it
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
        // --- patterns seen in real gym footage (ball hidden by backboard/net near the rim) ---
        Scenario("real: ball vanishes above the rim, reappears under the net (make)", listOf(Result.MAKE)) {
            Sim().apply {
                val d = descent(955f)
                run(d.take(6))            // last seen ~1U above the ring
                nothing(430)              // hidden by backboard + net
                run(line(Pair(948f, 560f), Pair(946f, 620f), 3)) // falls out of the net
            }.calls
        },
        Scenario("real: vanishes, then reappears below but well wide of the rim (miss)", listOf(Result.MISS)) {
            Sim().apply {
                run(descent(990f).take(6))
                nothing(400)
                run(line(Pair(1180f, 560f), Pair(1200f, 640f), 3)) // bounced off the side
            }.calls
        },
        Scenario("real: vanishes, a ball sitting on the floor nearby is not the shot", listOf(Result.MAKE)) {
            Sim().apply {
                val floor = listOf(Detection(1150f, 880f, 55f, 55f, 0.4f))
                repeat(30) { frame(null, extra = floor) }
                run(descent(955f).take(6), extra = floor)
                repeat(12) { frame(null, extra = floor) }
                run(line(Pair(950f, 560f), Pair(948f, 620f), 3), extra = floor)
            }.calls
        },
        Scenario("real: vanishes and never comes back = no call (add it by hand)", emptyList()) {
            Sim().apply { run(descent(955f).take(6)); nothing(4000) }.calls
        },
        // --- reported from the first home test ---
        Scenario("in line with the camera: front rim, drops in front of the net (miss)", listOf(Result.MISS)) {
            Sim().apply {
                val d = descent(950f).take(10)                         // looks like it's going in...
                run(d)
                run(line(d.last(), Pair(952f, 385f), 4), w = 58f)       // ...hits the rim, pops up toward the camera
                run(line(Pair(952f, 385f), Pair(956f, 640f), 9), w = 66f) // drops in front of the net: bigger on screen
            }.calls
        },
        Scenario("in line with the camera: swish, ball partly hidden by the net (make)", listOf(Result.MAKE)) {
            Sim().apply {
                val d = descent(950f).take(10)
                run(d)
                run(line(d.last(), Pair(951f, 600f), 6), w = 44f)       // smaller box: net covers part of it
            }.calls
        },
        Scenario("walking to a new spot with the ball near the camera is not a shot", emptyList()) {
            Sim().apply {
                // carried at chest height close to the camera: looks "above the rim" on screen
                val walk = (0 until 60).map { i -> Pair(600f + 12f * i, 330f + 5f * kotlin.math.sin(i / 3f)) }
                run(walk, w = 70f)
                run(line(walk.last(), Pair(1320f, 700f), 30), w = 70f) // then lowers it and keeps walking
                nothing(1500)
            }.calls
        },
        Scenario("picking the ball up under the hoop and walking away is not a shot", emptyList()) {
            Sim().apply {
                run(line(Pair(940f, 900f), Pair(945f, 330f), 25), w = 60f) // lifted up (close to camera, looks high)
                run(line(Pair(945f, 330f), Pair(700f, 360f), 30), w = 60f) // carried away
                run(line(Pair(700f, 360f), Pair(650f, 720f), 30), w = 60f) // lowered slowly
                nothing(1500)
            }.calls
        },
        Scenario("soft layup off the glass still counts (make)", listOf(Result.MAKE)) {
            Sim().apply {
                run(line(Pair(880f, 560f), Pair(935f, 330f), 10))
                run(line(Pair(935f, 330f), Pair(950f, 600f), 14))
            }.calls
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
