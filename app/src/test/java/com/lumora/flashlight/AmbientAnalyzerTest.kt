package com.lumora.flashlight

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientAnalyzerTest {
    private val analyzer = AmbientAnalyzer()

    private fun wave(rate: Int, frequency: Double): Flicker {
        val times = LongArray(rate) { it * 1_000_000_000L / rate }
        val values = FloatArray(rate) { (200 + 40 * sin(2 * PI * frequency * it / rate)).toFloat() }
        return analyzer.flicker(times, values)
    }

    @Test fun detectsFlickerOnlyWithEnoughSamples() {
        assertEquals(Flicker.HZ_50, wave(400, 100.0))
        assertEquals(Flicker.HZ_60, wave(400, 120.0))
        assertEquals(Flicker.UNKNOWN, wave(100, 100.0))
    }

    @Test fun classifiesDirectSun() {
        assertEquals(Scene.DIRECT_SUN, analyzer.classify(40_000f, false, 12))
    }

    @Test fun learningMovesGraduallyAndStaysBounded() {
        var learned = OverrideLearner.fromToggle(Learned(), true, 100f, 35f)
        assertTrue(learned.shift > 0f && learned.shift < .5f)
        repeat(50) { learned = OverrideLearner.fromToggle(learned, true, 1000f, 35f) }
        assertEquals(1f, learned.shift, .001f)
    }
}
