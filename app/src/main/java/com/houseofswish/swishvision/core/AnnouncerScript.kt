package com.houseofswish.swishvision.core

/**
 * What the announcer says after a make. Pure logic (no Android), so it can be tested.
 *
 * Every make: the session's make count ("12!").
 * Streaks: 3 = heating up, 4 = on fire, every 5 = bonus (5 Bonus, 10 Double bonus, 15 Triple bonus, ...).
 * Other makes: sometimes a classic call ("Bang!", "Nothing but net!" ...), never the same one twice in a row.
 */
class AnnouncerScript(private val flavorChance: Double = 0.35) {

    private var lastFlavor: String? = null

    companion object {
        val CALLS = listOf(
            "Bang!",
            "Yes!",
            "Right between the eyes!",
            "Cold-blooded!",
            "Nothing but net!",
            "Count it!",
            "Splash!",
            "Money!",
            "Boomshakalaka!",
        )
        const val DOWNTOWN = "From downtown!"

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
     * @param makes  makes so far this session, including this one
     * @param streak makes in a row, including this one
     * @param roll   0..1 random number (passed in so tests are repeatable)
     */
    fun forMake(makes: Int, streak: Int, player: String?, isThree: Boolean, roll: Double, pick: Int): String {
        val who = firstName(player)
        val milestone = when {
            streak >= 5 && streak % 5 == 0 -> bonus(streak / 5)
            streak == 3 -> if (who != null) "$who is heating up!" else "Heating up!"
            streak == 4 -> if (who != null) "$who is on fire!" else "On fire!"
            else -> null
        }
        val flavor = if (milestone == null && roll < flavorChance) {
            val pool = if (isThree) CALLS + DOWNTOWN + DOWNTOWN else CALLS // threes lean "From downtown!"
            val options = pool.filter { it != lastFlavor }
            options[Math.floorMod(pick, options.size)].also { lastFlavor = it }
        } else {
            null
        }
        return listOfNotNull("$makes!", milestone ?: flavor).joinToString(" ")
    }
}
