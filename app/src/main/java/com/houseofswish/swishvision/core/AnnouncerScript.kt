package com.houseofswish.swishvision.core

import kotlin.random.Random

/**
 * What the announcer does after each shot. Pure logic (no Android), so it can be tested.
 *
 * After a make: the phone voice says the make count ("12!"), then one of Shane's recorded clips may play:
 *   3 in a row  -> "He's heating up"        4 in a row -> "He's on fire"
 *   every 5     -> Bonus, Double bonus, Triple bonus, Quadruple bonus, Quintuple bonus (phone voice after that)
 *   threes      -> usually "From downtown" / "From the parking lot" / "From way downtown, bang"
 *   6+ in a row -> sometimes "En fuego" / "Dare I say en fuego"
 *   otherwise   -> about every other make, a classic call. Every call plays once before any repeats.
 * After a miss: "Not so fast my friend" when it ends a streak of 3+, "You got this" every 3 misses in a row.
 * When tracking starts: "We talkin' bout practice".
 */
class AnnouncerScript(
    private val rnd: Random = Random.Default,
    private val callChance: Double = 0.5,
    private val threeChance: Double = 0.75,
) {
    /** [count] is spoken by the phone voice; then [clip] plays, or [say] is spoken when there is no clip for it. */
    data class Call(val count: String, val clip: String? = null, val say: String? = null)

    companion object {
        val CALLS = listOf(
            "call_bang", "call_yes", "call_right_between_eyes", "call_cold_blooded", "call_nothing_but_net",
            "call_bottom_of_net", "call_splash", "call_money", "call_boomshakalaka", "call_booyah",
            "call_buckets", "call_dagger", "call_hocus_pocus", "call_straight_cash", "call_boom_dynamite",
            "call_cool_pillow", "call_rye_bread", "call_popcorn", "call_it_might_be", "call_no_regard",
        )
        val THREES = listOf("three_downtown", "three_parking_lot", "three_way_downtown")
        val HOT = listOf("streak_en_fuego", "streak_dare_en_fuego")
        const val HEATING_UP = "streak_heating_up"
        const val ON_FIRE = "streak_on_fire"
        val BONUS = listOf("bonus_1", "bonus_2", "bonus_3", "bonus_4", "bonus_5") // index = level - 1
        const val NOT_SO_FAST = "miss_not_so_fast"
        const val YOU_GOT_THIS = "miss_you_got_this"
        const val START = "start_practice"

        /** What each clip says: used by the phone voice if a clip can't be played. */
        val TEXT = mapOf(
            "call_bang" to "Bang!", "call_yes" to "Yes!", "call_right_between_eyes" to "Right between the eyes!",
            "call_cold_blooded" to "Cold-blooded!", "call_nothing_but_net" to "Nothing but net!",
            "call_bottom_of_net" to "Nothing but the bottom of the net!", "call_splash" to "Splash!",
            "call_money" to "Money!", "call_boomshakalaka" to "Boomshakalaka!", "call_booyah" to "Booyah!",
            "call_buckets" to "Buckets!", "call_dagger" to "Dagger!", "call_hocus_pocus" to "Hocus pocus!",
            "call_straight_cash" to "Straight cash, homie!", "call_boom_dynamite" to "And boom goes the dynamite!",
            "call_cool_pillow" to "As cool as the other side of the pillow!",
            "call_rye_bread" to "Get out the rye bread and mustard, Grandma!", "call_popcorn" to "Get your popcorn ready!",
            "call_it_might_be" to "It might be, it could be, it is!", "call_no_regard" to "With no regard for human life!",
            "three_downtown" to "From downtown!", "three_parking_lot" to "From the parking lot!",
            "three_way_downtown" to "From way downtown! Bang!", "streak_heating_up" to "He's heating up!",
            "streak_on_fire" to "He's on fire!", "streak_en_fuego" to "En fuego!", "streak_dare_en_fuego" to "Dare I say, en fuego!",
            "bonus_1" to "Bonus!", "bonus_2" to "Double bonus!", "bonus_3" to "Triple bonus!",
            "bonus_4" to "Quadruple bonus!", "bonus_5" to "Quintuple bonus!",
            "miss_not_so_fast" to "Not so fast, my friend!", "miss_you_got_this" to "You got this!",
            "start_practice" to "We talkin' bout practice!",
        )

        // index = level - 2 (level 2 = 10 in a row)
        private val MULTIPLIERS = listOf(
            "Double", "Triple", "Quadruple", "Quintuple",
            "Sextuple", "Septuple", "Octuple", "Nonuple", "Decuple",
        )

        /** 1 -> "Bonus!", 2 -> "Double bonus!", 3 -> "Triple bonus!", ... 10 -> "Decuple bonus!", 11 -> "Bonus times 11!" */
        fun bonus(level: Int): String = when {
            level <= 1 -> "Bonus!"
            level - 2 < MULTIPLIERS.size -> "${MULTIPLIERS[level - 2]} bonus!"
            else -> "Bonus times $level!"
        }

        /** First name only, cleaned up for speech. */
        fun firstName(name: String?): String? =
            name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() && it.length <= 20 }
    }

    /** Plays every clip in a pile once, in random order, before any repeats (and never the same one back to back). */
    private inner class Deck(private val all: List<String>) {
        private val left = ArrayList<String>()
        private var last: String? = null
        fun draw(): String {
            if (left.isEmpty()) {
                left += all.shuffled(rnd)
                if (left.size > 1 && left.last() == last) left.add(0, left.removeAt(left.size - 1))
            }
            return left.removeAt(left.size - 1).also { last = it }
        }
    }

    private val calls = Deck(CALLS)
    private val threes = Deck(THREES)
    private val hot = Deck(HOT)

    /**
     * @param makes  makes so far this session, including this one
     * @param streak makes in a row, including this one
     */
    fun forMake(makes: Int, streak: Int, isThree: Boolean): Call {
        val count = "$makes!"
        if (streak >= 5 && streak % 5 == 0) {
            val level = streak / 5
            return if (level <= BONUS.size) Call(count, clip = BONUS[level - 1]) else Call(count, say = bonus(level))
        }
        if (streak == 3) return Call(count, clip = HEATING_UP)
        if (streak == 4) return Call(count, clip = ON_FIRE)
        val clip = when {
            isThree -> if (rnd.nextDouble() < threeChance) {
                if (rnd.nextDouble() < 0.65) threes.draw() else calls.draw()
            } else null
            rnd.nextDouble() < callChance -> if (streak >= 6 && rnd.nextDouble() < 0.4) hot.draw() else calls.draw()
            else -> null
        }
        return Call(count, clip = clip)
    }

    /**
     * @param missesInRow  misses in a row, including this one
     * @param endedStreak  the make streak this miss just ended (0 if none)
     */
    fun forMiss(missesInRow: Int, endedStreak: Int): String? = when {
        endedStreak >= 3 -> NOT_SO_FAST
        missesInRow >= 3 && missesInRow % 3 == 0 -> YOU_GOT_THIS
        else -> null
    }
}
