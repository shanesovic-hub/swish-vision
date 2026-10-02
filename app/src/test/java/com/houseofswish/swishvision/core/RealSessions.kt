package com.houseofswish.swishvision.core

import java.io.File
import java.io.InputStream
import java.util.zip.GZIPInputStream

/**
 * Replays recorded ball detections from real sessions through the tracker and scores the calls
 * against what actually happened. Guards against changes that help one camera setup but hurt another.
 *
 * Each session: <name>.replay.gz (one line per analysed frame: "t rimX rimY rimW rimH | cx cy w h score; ...")
 * and <name>.truth.txt ("t kind truth": auto = the camera made a call there, manual = the player added a
 * shot the camera missed; truth MAKE, MISS or none).
 */
object RealSessions {

    data class Score(val name: String, val right: Int, val total: Int, val extra: Int, val wrong: List<String>)

    /** Floors: today's results. A change may not make any session worse than this. */
    private val FLOORS = mapOf(
        "basement_2026-10-01" to Pair(130, 2),      // right calls of 145, extra calls allowed
        "outdoor_layups_2026-10-01" to Pair(59, 1), // of 68
        "dusk_layups_2026-10-01" to Pair(81, 1),    // of 94 (the extra one is a miss the player logged 6 s late)
        "gym_test5" to Pair(27, 1),                 // of 30
    )

    fun runAll(dir: File? = null, verbose: Boolean = false): List<String> {
        val failures = ArrayList<String>()
        for ((name, floor) in FLOORS) {
            val s = score(name, dir)
            if (verbose) {
                println("${s.name}: ${s.right}/${s.total} right, ${s.extra} extra")
                s.wrong.forEach { println("   $it") }
            }
            if (s.right < floor.first) failures += "${s.name}: ${s.right}/${s.total} right (needs ${floor.first})"
            if (s.extra > floor.second) failures += "${s.name}: ${s.extra} extra calls (allowed ${floor.second})"
        }
        return failures
    }

    private fun open(name: String, dir: File?): InputStream =
        if (dir != null) File(dir, name).inputStream()
        else RealSessions::class.java.getResourceAsStream("/sessions/$name") ?: error("missing test session $name")

    fun score(name: String, dir: File? = null): Score {
        val calls = ArrayList<Triple<Long, Result, String>>()
        val tracker = ShotTracker()
        GZIPInputStream(open("$name.replay.gz", dir)).bufferedReader().useLines { lines ->
            for (ln in lines) {
                if (ln.isBlank()) continue
                val parts = ln.split("|")
                val head = parts[0].trim().split(" ").map { it.toFloat() }
                val t = head[0].toLong()
                val rim = if (head.size >= 5 && head[3] > 0) Box(head[1], head[2], head[3], head[4]) else null
                val dets = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }?.split(";")?.map {
                    val v = it.trim().split(" ").map(String::toFloat)
                    Detection(v[0], v[1], v[2], v[3], v[4])
                } ?: emptyList()
                tracker.update(t, dets, rim)?.let { calls += Triple(t, it, tracker.lastWhy) }
            }
        }
        val truth = open("$name.truth.txt", dir).bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { val p = it.trim().split(Regex("\\s+")); Triple(p[0].toLong(), p[1], p[2]) }
        val autos = truth.filter { it.second == "auto" }.map { it.first }
        val used = HashSet<Long>()
        var right = 0
        val wrong = ArrayList<String>()
        for ((t, kind, want) in truth) {
            val match = calls.firstOrNull { c ->
                c.first !in used && if (kind == "auto") {
                    kotlin.math.abs(c.first - t) <= 1200
                } else {
                    // The camera missed this shot: a call in the 6 s before the player added it, not one of the camera's own.
                    t - c.first in 0..6000 && autos.none { kotlin.math.abs(c.first - it) <= 1200 }
                }
            }
            if (match != null) used += match.first
            val got = match?.second?.name ?: "none"
            if (got == want) right++ else wrong += "$t $kind: wanted $want, got $got${match?.let { " (${it.third})" } ?: ""}"
        }
        val extra = calls.count { it.first !in used }
        return Score(name, right, truth.size, extra, wrong)
    }
}
