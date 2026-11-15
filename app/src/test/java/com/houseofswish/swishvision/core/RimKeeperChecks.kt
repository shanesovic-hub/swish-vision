package com.houseofswish.swishvision.core

import java.io.File
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The rim box stays on the hoop when the phone is moved, and doesn't wander when it isn't.
 * Made-up camera moves, plus the hoop sightings from five real November sessions.
 */
object RimKeeperChecks {
    private fun check(cond: Boolean, msg: String, out: MutableList<String>) { if (!cond) out += msg }

    private const val W = 1920
    private const val H = 1080
    private const val STEP = 66L // ~15 analysed frames a second

    private fun hoopAt(cx: Float, cy: Float, w: Float = 90f) = Detection(cx, cy, w, 0.88f * w, 0.95f)

    /** The rim box the app would make for a hoop detection. */
    private fun rimOf(d: Detection) = HoopFinder.toRimBox(d)

    private fun onHoop(rim: Box?, d: Detection) = rim != null && abs(rim.cx - rimOf(d).cx) < 0.15f * d.w && abs(rim.y - rimOf(d).y) < 0.15f * d.w

    /**
     * Plays a made-up session the way the app runs it: the ball finder only sees the crop around
     * the rim box, except on the whole-picture looks the keeper asks for.
     * [world] says which hoops are in the picture at time t; returns every change.
     */
    private fun play(k: RimKeeper, from: Long, to: Long, quiet: (Long) -> Boolean = { true },
                     world: (Long) -> List<Detection>): List<Pair<Long, RimKeeper.Change>> {
        val changes = ArrayList<Pair<Long, RimKeeper.Change>>()
        var t = from
        while (t < to) {
            val r = k.rim
            val look = k.wantLook(t, quiet(t))
            val roi = if (r != null && !look) Roi.aroundRim(r, W, H, 640) else Roi.full(W, H, 640)
            val visible = world(t).filter { it.cx in roi.x.toFloat()..(roi.x + roi.w).toFloat() && it.cy in roi.y.toFloat()..(roi.y + roi.h).toFloat() }
            k.update(t, visible)?.let { changes += t to it }
            t += STEP
        }
        return changes
    }

    fun runAll(dir: File? = null, verbose: Boolean = false): List<String> {
        val f = ArrayList<String>()
        val a = hoopAt(960f, 195f)
        val b = hoopAt(1500f, 260f, 80f) // where the hoop is after the phone is turned

        // 1. Finds the hoop at the start, in about half a second.
        run {
            val k = RimKeeper()
            val ch = play(k, 0, 3000) { listOf(a) }
            check(ch.firstOrNull()?.second?.kind == RimKeeper.Kind.FOUND, "start: hoop not found ($ch)", f)
            check((ch.firstOrNull()?.first ?: 9999) <= 700, "start: found too slowly (${ch.firstOrNull()?.first} ms)", f)
            check(ch.size == 1, "start: box moved on a steady hoop ($ch)", f)
        }

        // 2. Phone turned to a new angle: the hoop is now far outside the old crop. Box finds it again by itself.
        run {
            val k = RimKeeper()
            val ch = play(k, 0, 15_000) { t -> listOf(if (t < 5000) a else b) }
            val moved = ch.firstOrNull { it.second.kind == RimKeeper.Kind.MOVED }
            check(moved != null, "new angle: box never found the rim again ($ch)", f)
            if (moved != null) check(moved.first - 5000 <= 2500, "new angle: took ${moved.first - 5000} ms", f)
            check(onHoop(k.rim, b), "new angle: box not on the hoop (${k.rim})", f)
        }

        // 3. Phone picked up and carried (no hoop in view for a few seconds), then set down somewhere new.
        run {
            val k = RimKeeper()
            val ch = play(k, 0, 15_000) { t -> when { t < 4000 -> listOf(a); t < 7000 -> emptyList(); else -> listOf(b) } }
            check(onHoop(k.rim, b), "carried: box not on the hoop (${k.rim}, $ch)", f)
        }

        // 4. Phone bumped: the hoop shifts about half a rim width (the Nov 11 session). The box catches up fast.
        run {
            val k = RimKeeper()
            val bumped = hoopAt(1010f, 197f)
            val ch = play(k, 0, 6000) { t -> listOf(if (t < 3000) a else bumped) }
            val at = ch.lastOrNull()?.first ?: 0
            check(onHoop(k.rim, bumped), "bump: box not on the hoop (${k.rim})", f)
            check(at - 3000 <= 1000, "bump: still catching up ${at - 3000} ms later", f)
        }

        // 5. A slow pan (hoop slides across the picture over two seconds): the box rides along.
        run {
            val k = RimKeeper()
            play(k, 0, 9000) { t ->
                val x = when { t < 3000 -> 960f; t < 5000 -> 960f + (t - 3000) * 0.25f; else -> 1460f }
                listOf(hoopAt(x, 195f))
            }
            check(onHoop(k.rim, hoopAt(1460f, 195f)), "pan: box not on the hoop (${k.rim})", f)
        }

        // 6. Hoop blocked (player in front, glare) for 8 seconds, nothing else in view: the box stays put.
        run {
            val k = RimKeeper()
            val ch = play(k, 0, 16_000) { t -> if (t in 4000..12_000) emptyList() else listOf(a) }
            check(ch.size == 1 && onHoop(k.rim, a), "blocked: box moved ($ch)", f)
        }

        // 7. A second hoop in view all along; the player's hoop blocked for 4 seconds: the box doesn't jump to the other one.
        run {
            val k = RimKeeper()
            val other = hoopAt(300f, 240f, 70f)
            val ch = play(k, 0, 14_000) { t -> if (t in 5000..9000) listOf(other) else listOf(a, other) }
            check(ch.none { it.second.kind == RimKeeper.Kind.MOVED || it.second.kind == RimKeeper.Kind.BUMPED } && onHoop(k.rim, a),
                "second hoop: box jumped ($ch)", f)
        }

        // 8. One-off false hoop somewhere else (a frame here and there): ignored.
        run {
            val k = RimKeeper()
            val ghost = hoopAt(1600f, 600f)
            val ch = play(k, 0, 12_000) { t -> if ((t / STEP) % 20 == 0L) listOf(ghost) else listOf(a) }
            check(ch.size == 1, "ghost: box moved ($ch)", f)
        }

        // 9. The player drew a bigger box, a bit low: after the phone moves, the box keeps that shape on the hoop.
        run {
            val k = RimKeeper()
            play(k, 0, 2000) { listOf(a) }
            val ra = rimOf(a)
            val mine = Box(ra.x - 10f, ra.y + 8f, ra.w + 20f, ra.h + 12f)
            k.setManual(mine, 2000)
            play(k, 2000, 12_000) { t -> listOf(if (t < 5000) a else b) }
            val rb = rimOf(b)
            val s = rb.w / ra.w
            val want = Box(rb.x - 10f * s, rb.y + 8f * s, (ra.w + 20f) * s, (ra.h + 12f) * s)
            val got = k.rim
            check(got != null && abs(got.x - want.x) < 6 && abs(got.y - want.y) < 6 && abs(got.w - want.w) < 4,
                "drawn box: lost the player's shape after the move (got $got, want $want)", f)
        }

        // 10. The player drew a box where the hoop finder sees no hoop (it can't see theirs): the box is left alone.
        run {
            val k = RimKeeper()
            k.setManual(Box(200f, 500f, 80f, 48f), 0)
            val ch = play(k, 0, 10_000) { listOf(a) }
            check(ch.isEmpty() && k.rim == Box(200f, 500f, 80f, 48f), "drawn box with no hoop: moved ($ch)", f)
        }

        // 11. While the box is off the hoop (before it jumps), shots aren't counted against it.
        run {
            val k = RimKeeper()
            var untrusted = 0
            var t = 0L
            while (t < 9000) {
                val vis = if (t < 3000) listOf(a) else listOf(b) // whole picture each frame, to watch it closely
                val c = k.update(t, vis)
                if (t > 3800 && c == null && k.rim?.let { onHoop(it, a) } == true && !k.callsTrusted(t)) untrusted++
                t += STEP
            }
            check(untrusted > 0, "box off the hoop but shots still trusted", f)
            check(k.callsTrusted(t), "shots still not trusted after the box found the hoop", f)
        }

        // 12. Tapping Rim starts a fresh search.
        run {
            val k = RimKeeper()
            play(k, 0, 2000) { listOf(a) }
            k.clear()
            check(k.rim == null, "clear: rim still set", f)
            val ch = play(k, 2000, 4000) { listOf(b) }
            check(ch.firstOrNull()?.second?.kind == RimKeeper.Kind.FOUND && onHoop(k.rim, b), "clear: didn't find the hoop again ($ch)", f)
        }

        // Real sessions: the hoop sightings the app recorded. Phone not moved on purpose in these.
        for ((name, maxJumps) in REAL) {
            val r = replay(name, dir)
            if (verbose) println("rim keeper $name: on the hoop ${"%.1f".format(r.onPct)}% of hoop sightings, ${r.jumps} jumps ${r.jumpLog}")
            check(r.onPct >= 99f, "$name: box on the hoop only ${"%.1f".format(r.onPct)}% of the time", f)
            check(r.jumps <= maxJumps, "$name: box jumped ${r.jumps} times (allowed $maxJumps) ${r.jumpLog}", f)
        }
        return f
    }

    /** Sessions and the most jumps allowed: the Nov 11 4:24 one had the phone bumped and then turned. */
    val REAL = linkedMapOf(
        "driveway_nov_2026-11-11_1624" to 3,
        "driveway_nov_2026-11-11_1654" to 0,
        "driveway_nov_2026-11-11_2007" to 0,
        "driveway_nov_2026-11-12_1638" to 0,
        "driveway_nov_2026-11-13_1546" to 0,
    )

    class Replay(val onPct: Float, val jumps: Int, val jumpLog: List<String>)

    fun replay(name: String, dir: File? = null): Replay {
        val input = if (dir != null) File(dir, "$name.hoops.gz").inputStream()
        else RimKeeperChecks::class.java.getResourceAsStream("/sessions/$name.hoops.gz") ?: error("missing $name.hoops.gz")
        val k = RimKeeper()
        var seen = 0; var on = 0; var jumps = 0
        val log = ArrayList<String>()
        var t0 = -1L
        GZIPInputStream(input).bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (ln.isBlank()) continue
                val (head, tail) = ln.split("|").let { it[0].trim() to it.getOrElse(1) { "" }.trim() }
                val t = head.split(" ")[0].toLong()
                if (t0 < 0) t0 = t
                val hoops = if (tail.isEmpty()) emptyList() else tail.split(";").map { p ->
                    val v = p.trim().split(" ").map { it.toFloat() }
                    Detection(v[0], v[1], v[2], v[3], v[4])
                }
                val c = k.update(t, hoops)
                if (c != null && c.kind != RimKeeper.Kind.FOLLOW && c.kind != RimKeeper.Kind.FOUND) {
                    jumps++; log += "${c.kind.name.lowercase()}@${(t - t0) / 1000}s"
                }
                val best = hoops.filter { it.score >= 0.5f }.maxByOrNull { it.score } ?: continue
                if (k.rim == null) continue
                seen++
                if (RimKeeper.near(k.rim!!, rimOf(best)) && hypot(k.rim!!.cx - rimOf(best).cx, k.rim!!.cy - rimOf(best).cy) < 0.3f * best.w) on++
            }
        }
        return Replay(if (seen == 0) 0f else 100f * on / seen, jumps, log)
    }
}
