package com.houseofswish.swishvision.core

import org.junit.Assert.assertTrue
import org.junit.Test

class CoreTest {
    @Test
    fun trackerScenarios() {
        val failures = TrackerScenarios.runAll()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun realSessions() {
        val failures = RealSessions.runAll(verbose = true)
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun sessionDecoderAndRoi() {
        val failures = SessionAndDecoderChecks.runAll()
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }
}
