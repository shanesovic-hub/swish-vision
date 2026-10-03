package com.houseofswish.swishvision.core

import kotlin.random.Random

/**
 * What the announcer does after each shot. Pure logic (no Android), so it can be tested.
 *
 * Every make: the phone voice says the make count ("12!"), then one of Shane's recorded clips plays:
 *   3 in a row  -> "He's heating up"        4 in a row -> "He's on fire"
 *   every 5     -> Bonus, Double bonus, Triple bonus, Quadruple bonus, Quintuple bonus (phone voice after that)
 *   otherwise   -> a call. Every call plays once before any call repeats. Threes lean toward the
 *                  downtown calls, and long streaks (6+) toward "En fuego" / "Dare I say en fuego".
 * Every miss: "Not so fast my friend" when it ends a streak of 3+, "You got this" every 3 misses in a row,
 *   otherwise a miss comment (each once before any repeats). Miss comments without a recording yet are
 *   spoken by the phone voice.
 * When tracking starts: "We talkin' bout practice".
 */
class AnnouncerScript(
    private val rnd: Random = Random.Default,
    private val leanChance: Double = 0.6,
) {
    /** [count] is spoken by the phone voice; then [clip] plays, or [say] is spoken when there is no clip for it. */
    data class Call(val count: String? = null, val clip: String? = null, val say: String? = null)

    companion object {
        val CALLS = listOf(
            "call_bang", "call_yes", "call_right_between_eyes", "call_cold_blooded", "call_nothing_but_net",
            "call_bottom_of_net", "call_splash", "call_money", "call_boomshakalaka", "call_booyah",
            "call_buckets", "call_dagger", "call_hocus_pocus", "call_straight_cash", "call_boom_dynamite",
            "call_cool_pillow", "call_rye_bread", "call_popcorn", "call_it_might_be", "call_no_regard",
            "call_abracadabra", "call_entertained", "call_triple_double_rap", "call_great_scott", "call_the_force", "call_plan_together", "call_inconceivable", "call_like_a_glove",
            "call_mashed_potatoes", "call_dont_need_roads", "call_show_me_money", "call_infinity_beyond", "call_sixty_percent", "call_your_mama",
        )
        val THREES = listOf("three_downtown", "three_parking_lot", "three_way_downtown")
        val HOT = listOf("streak_en_fuego", "streak_dare_en_fuego")
        const val HEATING_UP = "streak_heating_up"
        const val ON_FIRE = "streak_on_fire"
        val BONUS = listOf("bonus_1", "bonus_2", "bonus_3", "bonus_4", "bonus_5") // index = level - 1
        val MISSES = listOf(
            "miss_no_good", "miss_brick", "miss_clank", "miss_not_this_time", "miss_off_the_mark",
            "miss_shake_it_off", "miss_next_one", "miss_keep_shooting", "miss_so_close", "miss_reload",
            "miss_stay_with_it", "miss_nope", "miss_line_it_up", "miss_short_memory",
            "miss_houston", "miss_bit_outside", "miss_theres_a_chance", "miss_killing_me_smalls",
        )
        /** Miss comments Shane recorded (the rest are spoken by the phone voice). */
        val RECORDED_MISSES = listOf("miss_houston", "miss_bit_outside", "miss_theres_a_chance", "miss_killing_me_smalls")
        const val SIX_SEVEN = "call_six_seven" // only ever on the 67th make
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
            "miss_no_good" to "No good!", "miss_brick" to "Brick!", "miss_clank" to "Clank!",
            "miss_not_this_time" to "Not this time!", "miss_off_the_mark" to "Off the mark!",
            "miss_shake_it_off" to "Shake it off!", "miss_next_one" to "Next one's going down!",
            "miss_keep_shooting" to "Keep shooting!", "miss_so_close" to "So close!", "miss_reload" to "Reload!",
            "miss_stay_with_it" to "Stay with it!", "miss_nope" to "Nope!", "miss_line_it_up" to "Line it up again!",
            "miss_short_memory" to "Short memory!",
            "call_abracadabra" to "Abracadabra!",
            "call_entertained" to "Are you not entertained?",
            "call_triple_double_rap" to "Get me on the court and I'm trouble, last week messed around and got a triple double!",
            "call_great_scott" to "Great Scott!",
            "call_the_force" to "I am one with the Force, and the Force is with me!",
            "call_plan_together" to "I love it when a plan comes together!",
            "call_inconceivable" to "Inconceivable!",
            "call_like_a_glove" to "Like a glove!",
            "call_mashed_potatoes" to "Mashed potatoes!",
            "call_dont_need_roads" to "Roads? Where we're going, we don't need roads.",
            "call_show_me_money" to "Show me the money!",
            "call_infinity_beyond" to "To infinity and beyond!",
            "call_six_seven" to "Six seven!",
            "call_sixty_percent" to "Sixty percent of the time, it works every time.",
            "call_your_mama" to "Your mama!",
            "miss_houston" to "Houston, we have a problem.",
            "miss_bit_outside" to "Just a bit outside.",
            "miss_theres_a_chance" to "So you're telling me there's a chance.",
            "miss_killing_me_smalls" to "You're killing me, Smalls.",
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

    /**
     * Every clip plays once before any clip repeats (and never the same one back to back).
     * Only clips that fit the moment can play (downtown calls only on threes, en fuego only on a long
     * streak); when the ones that fit have all played, they start over.
     */
    private inner class Rotation {
        private val played = HashSet<String>()
        private var last: String? = null
        fun draw(fits: List<String>, lean: List<String> = emptyList()): String {
            var fresh = fits.filter { it !in played }
            if (fresh.isEmpty()) {
                played.removeAll(fits.toSet())
                fresh = fits.filter { it != last }.ifEmpty { fits }
            }
            val leaning = fresh.filter { it in lean }
            val pick = if (leaning.isNotEmpty() && rnd.nextDouble() < leanChance) leaning.random(rnd) else fresh.random(rnd)
            played += pick
            last = pick
            return pick
        }
    }

    private val makeCalls = Rotation()
    private val missCalls = Rotation()

    /**
     * @param makes  makes so far this session, including this one
     * @param streak makes in a row, including this one
     */
    fun forMake(makes: Int, streak: Int, isThree: Boolean): Call {
        val count = "$makes!"
        if (makes == 67) return Call(count, clip = SIX_SEVEN) // always, and only, on the 67th make
        if (streak >= 5 && streak % 5 == 0) {
            val level = streak / 5
            return if (level <= BONUS.size) Call(count, clip = BONUS[level - 1]) else Call(count, say = bonus(level))
        }
        if (streak == 3) return Call(count, clip = HEATING_UP)
        if (streak == 4) return Call(count, clip = ON_FIRE)
        val hotStreak = streak >= 6
        val fits = CALLS + (if (isThree) THREES else emptyList()) + (if (hotStreak) HOT else emptyList())
        val lean = (if (isThree) THREES else emptyList()) + (if (hotStreak) HOT else emptyList())
        return Call(count, clip = makeCalls.draw(fits, lean))
    }

    /**
     * @param missesInRow  misses in a row, including this one
     * @param endedStreak  the make streak this miss just ended (0 if none)
     */
    fun forMiss(missesInRow: Int, endedStreak: Int): Call = when {
        endedStreak >= 3 -> Call(clip = NOT_SO_FAST)
        missesInRow >= 3 && missesInRow % 3 == 0 -> Call(clip = YOU_GOT_THIS)
        else -> Call(clip = missCalls.draw(MISSES, lean = RECORDED_MISSES)) // recorded ones come up more often
    }
}
