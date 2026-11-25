package com.lumora.flashlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightPatternsTest {
    @Test fun strobeAlternatesEvenly() {
        val steps = LightPatterns.steps(LightMode.STROBE)
        assertEquals(listOf(true, false), steps.map { it.on })
        assertEquals(1, steps.map { it.millis }.toSet().size)
    }

    @Test fun sosIsThreeShortThreeLongThreeShort() {
        val steps = LightPatterns.steps(LightMode.SOS)
        val flashes = steps.filter { it.on }.map { it.millis }
        assertEquals(
            List(3) { LightPatterns.DOT } + List(3) { LightPatterns.DASH } + List(3) { LightPatterns.DOT },
            flashes
        )
    }

    @Test fun sosAlternatesOnAndOffAndEndsWithWordGap() {
        val steps = LightPatterns.steps(LightMode.SOS)
        steps.forEachIndexed { index, step -> assertEquals(index % 2 == 0, step.on) }
        assertEquals(Step(false, LightPatterns.WORD_GAP), steps.last())
        assertTrue(steps.count { !it.on && it.millis == LightPatterns.LETTER_GAP } == 2)
    }
}
