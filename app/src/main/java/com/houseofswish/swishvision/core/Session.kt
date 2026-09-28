package com.houseofswish.swishvision.core

/** The four rows Swish Quest already logs. Keys match the app's shotTypes keys. */
enum class ShotType(val key: String, val label: String) {
    LAYUP("layups", "LAYUP"),
    MID("midrange", "MID"),
    FT("ft", "FT"),
    THREE("three", "3PT"),
}

enum class Method { AUTO, MANUAL }

data class Shot(
    val t: Long,
    var result: Result,
    val type: ShotType,
    val method: Method,
    var flipped: Boolean = false, // auto call the player marked as wrong
)

data class TypeLine(val made: Int, val attempted: Int)

/** One shooting session plus how well the camera did. */
class Session(val startedAt: Long) {
    val shots = ArrayList<Shot>()
    var phantoms = 0 // auto calls that were undone (nothing actually happened)
        private set
    var endedAt: Long? = null

    fun add(t: Long, result: Result, type: ShotType, method: Method): Shot =
        Shot(t, result, type, method).also { shots += it }

    /** Remove the last shot. An undone camera call counts against the camera. */
    fun undo(): Shot? {
        val s = shots.removeLastOrNull() ?: return null
        if (s.method == Method.AUTO && !s.flipped) phantoms++
        return s
    }

    /** "Wrong call": flip the last shot. Pressing again flips it back. */
    fun flipLast(): Shot? {
        val s = shots.lastOrNull() ?: return null
        s.result = if (s.result == Result.MAKE) Result.MISS else Result.MAKE
        if (s.method == Method.AUTO) s.flipped = !s.flipped
        return s
    }

    val makes get() = shots.count { it.result == Result.MAKE }
    val attempts get() = shots.size
    val pct get() = if (attempts == 0) 0 else Math.round(100f * makes / attempts)

    val currentStreak: Int
        get() {
            var n = 0
            for (i in shots.indices.reversed()) if (shots[i].result == Result.MAKE) n++ else break
            return n
        }

    val bestStreak: Int
        get() {
            var best = 0
            var run = 0
            for (s in shots) {
                run = if (s.result == Result.MAKE) run + 1 else 0
                if (run > best) best = run
            }
            return best
        }

    /** Percent over the last 10 shots, or null before any shots. */
    val last10Pct: Int?
        get() {
            val last = shots.takeLast(10)
            if (last.isEmpty()) return null
            return Math.round(100f * last.count { it.result == Result.MAKE } / last.size)
        }

    fun byType(): Map<ShotType, TypeLine> = ShotType.values().associateWith { type ->
        val list = shots.filter { it.type == type }
        TypeLine(list.count { it.result == Result.MAKE }, list.size)
    }

    // --- Camera report card ---
    val cameraRight get() = shots.count { it.method == Method.AUTO && !it.flipped }
    val cameraFlipped get() = shots.count { it.method == Method.AUTO && it.flipped }
    val cameraMissed get() = shots.count { it.method == Method.MANUAL } // shots it never saw
    val cameraWrong get() = cameraFlipped + cameraMissed + phantoms
    val cameraAccuracy: Int?
        get() {
            val total = cameraRight + cameraWrong
            return if (total == 0) null else Math.round(100f * cameraRight / total)
        }

    fun minutes(now: Long): Int {
        val end = endedAt ?: now
        return maxOf(1, Math.round((end - startedAt) / 60000f))
    }

    /** Swish Quest-ready JSON: the four rows, the time, and the shot-by-shot detail. */
    fun toJson(now: Long, avgFps: Float, model: String): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"app\": \"swish-vision\",\n")
        sb.append("  \"version\": 1,\n")
        sb.append("  \"model\": \"").append(model).append("\",\n")
        sb.append("  \"startedAt\": ").append(startedAt).append(",\n")
        sb.append("  \"endedAt\": ").append(endedAt ?: now).append(",\n")
        sb.append("  \"minutes\": ").append(minutes(now)).append(",\n")
        sb.append("  \"shots\": ").append(attempts).append(",\n")
        sb.append("  \"makes\": ").append(makes).append(",\n")
        sb.append("  \"bestStreak\": ").append(bestStreak).append(",\n")
        sb.append("  \"avgFps\": ").append(String.format(java.util.Locale.US, "%.1f", avgFps)).append(",\n")
        sb.append("  \"camera\": {\"right\": ").append(cameraRight)
            .append(", \"flipped\": ").append(cameraFlipped)
            .append(", \"missed\": ").append(cameraMissed)
            .append(", \"phantoms\": ").append(phantoms).append("},\n")
        sb.append("  \"shotTypes\": {")
        val lines = byType().filterValues { it.attempted > 0 }.entries
        lines.forEachIndexed { i, (type, line) ->
            if (i > 0) sb.append(", ")
            sb.append("\"").append(type.key).append("\": {\"attempted\": ").append(line.attempted)
                .append(", \"made\": ").append(line.made).append("}")
        }
        sb.append("},\n")
        sb.append("  \"log\": [")
        shots.forEachIndexed { i, s ->
            if (i > 0) sb.append(",")
            sb.append("\n    {\"t\": ").append(s.t - startedAt)
                .append(", \"r\": \"").append(if (s.result == Result.MAKE) "make" else "miss")
                .append("\", \"type\": \"").append(s.type.key)
                .append("\", \"m\": \"").append(if (s.method == Method.AUTO) "auto" else "manual")
                .append("\"").append(if (s.flipped) ", \"flipped\": true" else "").append("}")
        }
        sb.append(if (shots.isEmpty()) "]\n" else "\n  ]\n")
        sb.append("}\n")
        return sb.toString()
    }
}
