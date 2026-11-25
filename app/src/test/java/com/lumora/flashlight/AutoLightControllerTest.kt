package com.lumora.flashlight

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoLightControllerTest {
    private var now = 1000L

    private fun controller(motion: Boolean = false) =
        AutoLightController(Sources(true, true, motion, true)).also { it.start(false, 10, now) }

    private fun AutoLightController.run(lux: Float, millis: Long): Output {
        val end = now + millis
        var output = output(now)
        while (now < end) {
            onLux(lux)
            now += 250
            output = tick(now)
        }
        return output
    }

    private val text = object : TextSource {
        override fun get(id: Int, vararg args: Any) = (listOf<Any>(id) + args).joinToString("|")
    }

    @Test fun describesReasonAndResult() {
        val percent = text.get(R.string.result_percent, 90)
        assertEquals(text.get(R.string.reason_dark, percent), reasonLine(text, Reason.DARK, 9, true))
        assertEquals(text.get(R.string.reason_covered_paused), reasonLine(text, Reason.COVERED, 0, true))
        assertEquals(
            text.get(R.string.reason_dim, text.get(R.string.result_on)),
            reasonLine(text, Reason.DIM, 5, false)
        )
    }

    @Test fun turnsOnOnlyAfterDwell() {
        val c = controller()
        assertEquals(0, c.run(2f, 2000).strength)
        assertEquals(10, c.run(2f, 2000).strength)
    }

    @Test fun ignoresShortSpike() {
        val c = controller()
        c.run(2f, 5000)
        c.run(5000f, 500)
        assertEquals(10, c.run(2f, 3000).strength)
    }

    @Test fun neverTurnsOnWhenCovered() {
        val c = controller()
        c.onProximity(true, now)
        val output = c.run(0f, 10_000)
        assertEquals(0, output.strength)
        assertEquals(Reason.COVERED, output.reason)
    }

    @Test fun userStrengthHoldsForTwoMinutes() {
        val c = controller()
        c.run(2f, 5000)
        c.userStrength(3, now)
        assertEquals(3, c.run(2f, 119_000).strength)
        assertEquals(10, c.run(2f, 5000).strength)
    }

    @Test fun thermalStatusCapsAndRestores() {
        val c = controller()
        c.run(2f, 5000)
        c.onThermal(3)
        assertEquals(4, c.output(now).strength)
        c.onThermal(0)
        assertEquals(10, c.output(now).strength)
    }

    @Test fun curveFallsAsLuxRises() {
        val c = controller()
        val values = listOf(0.0, 20.0, 50.0, 100.0, 220.0).map { c.targetFor(it) }
        assertEquals(values.sortedDescending(), values)
        assertEquals(10.0, values.first(), .001)
        assertEquals(4.0, values.last(), .001)
    }

    @Test fun pausesWhenStillAndResumesOnLightChange() {
        val c = controller(motion = true)
        assertFalse(c.run(500f, 12_000).lightSensorNeeded)
        assertTrue(c.run(2f, 25_000).strength > 0)
    }

    @Test fun brightCalibrationNeedsAtLeastThirtyLux() {
        val c = controller()
        c.run(20f, 3000)
        assertNull(c.calibrate(bright = true))
    }

    @Test fun darkCalibrationInLitRoomStaysValid() {
        val c = controller()
        c.run(100f, 3000)
        val result = c.calibrate(bright = false)
        assertTrue(result != null && result.valid && result.darkLux > 90f)
    }

    @Test fun brightCalibrationLowersDarkLevelWhenNeeded() {
        val c = controller()
        c.run(60f, 3000)
        val result = c.calibrate(bright = true)
        assertTrue(result != null && result.valid && result.brightLux > 50f && result.darkLux < 20f)
    }

    @Test fun turnsOffWhenLaidFaceDownAndStaysOff() {
        val c = controller()
        assertEquals(10, c.run(2f, 5000).strength)
        c.onAccelerometer(0f, 0f, -9.8f, now)
        assertEquals(0, c.run(2f, 6000).strength)
        assertEquals(0, c.run(2f, 3000).strength)
    }

    @Test fun turnsOffInOrdinaryIndoorLight() {
        val c = controller()
        assertEquals(10, c.run(2f, 5000).strength)
        assertEquals(0, c.run(120f, 8000).strength)
    }
}
