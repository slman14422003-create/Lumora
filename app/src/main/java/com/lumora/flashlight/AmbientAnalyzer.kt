package com.lumora.flashlight

import androidx.annotation.StringRes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.sin

enum class Scene(@StringRes val title: Int, @StringRes val tip: Int) {
    UNKNOWN(R.string.scene_unknown, R.string.scene_unknown_tip),
    DARK(R.string.scene_dark, R.string.scene_dark_tip),
    DIM(R.string.scene_dim, R.string.scene_dim_tip),
    INDOOR(R.string.scene_indoor, R.string.scene_indoor_tip),
    DAYLIGHT(R.string.scene_daylight, R.string.scene_daylight_tip),
    DIRECT_SUN(R.string.scene_sun, R.string.scene_sun_tip),
    COVERED(R.string.scene_covered, R.string.scene_covered_tip)
}

enum class Warmth(@StringRes val title: Int) {
    UNKNOWN(R.string.warmth_unknown),
    WARM(R.string.warmth_warm),
    NEUTRAL(R.string.warmth_neutral),
    COOL(R.string.warmth_cool)
}

enum class Flicker(@StringRes val title: Int) {
    UNKNOWN(R.string.flicker_unknown),
    STEADY(R.string.flicker_steady),
    HZ_50(R.string.flicker_50),
    HZ_60(R.string.flicker_60)
}

class AmbientAnalyzer {
    var scene = Scene.UNKNOWN
        private set
    private var candidate = Scene.UNKNOWN
    private var candidateCount = 0
    private var zeroSince: Long? = null

    fun reset() {
        scene = Scene.UNKNOWN
        candidate = Scene.UNKNOWN
        candidateCount = 0
        zeroSince = null
    }

    fun classify(lux: Float?, covered: Boolean, hour: Int): Scene {
        val night = hour >= 20 || hour < 6
        val next = when {
            covered -> Scene.COVERED
            lux == null -> Scene.UNKNOWN
            lux < 10f -> Scene.DARK
            lux < 50f -> Scene.DIM
            lux < (if (night) 3000f else 1000f) -> Scene.INDOOR
            lux < 25_000f -> Scene.DAYLIGHT
            else -> Scene.DIRECT_SUN
        }
        if (next != candidate) {
            candidate = next
            candidateCount = 0
        }
        candidateCount++
        if (scene == Scene.UNKNOWN || next == Scene.COVERED || candidateCount >= 3) scene = next
        return scene
    }

    fun stuckAtZero(lux: Float?, moving: Boolean, now: Long): Boolean {
        if (lux == null || lux >= 1f) {
            zeroSince = null
            return false
        }
        if (zeroSince == null && moving) zeroSince = now
        return zeroSince?.let { now - it >= 8000L } == true
    }

    fun blocked(sensorLux: Float?, cameraLux: Float?) =
        sensorLux != null && cameraLux != null && sensorLux < 2f && cameraLux > 30f

    fun flicker(timesNanos: LongArray, values: FloatArray): Flicker {
        val count = minOf(timesNanos.size, values.size)
        if (count < 64) return Flicker.UNKNOWN
        val seconds = (timesNanos[count - 1] - timesNanos[0]) / 1e9
        if (seconds <= 0 || (count - 1) / seconds < 250.0) return Flicker.UNKNOWN
        var sum = 0.0
        for (i in 0 until count) sum += values[i]
        val mean = sum / count
        if (mean < 5.0) return Flicker.UNKNOWN
        fun depth(frequency: Double): Double {
            var real = 0.0
            var imaginary = 0.0
            for (i in 0 until count) {
                val angle = 2 * PI * frequency * (timesNanos[i] - timesNanos[0]) / 1e9
                real += (values[i] - mean) * cos(angle)
                imaginary += (values[i] - mean) * sin(angle)
            }
            return 2 * hypot(real, imaginary) / count / mean
        }
        val at100 = depth(100.0)
        val at120 = depth(120.0)
        return when {
            maxOf(at100, at120) < .03 -> Flicker.STEADY
            at100 > at120 -> Flicker.HZ_50
            else -> Flicker.HZ_60
        }
    }

    fun warmth(redGain: Float, blueGain: Float): Warmth {
        if (redGain <= 0f || blueGain <= 0f) return Warmth.UNKNOWN
        val ratio = blueGain / redGain
        return when {
            ratio > 1.25f -> Warmth.WARM
            ratio < .75f -> Warmth.COOL
            else -> Warmth.NEUTRAL
        }
    }

    fun closeSubjectCap(distanceMeters: Float?) = when {
        distanceMeters == null -> 10
        distanceMeters < .3f -> 4
        distanceMeters < .6f -> 7
        else -> 10
    }
}

data class Learned(val shift: Float = 0f, val bias: Float = 0f)

object OverrideLearner {
    fun fromToggle(old: Learned, turnedOn: Boolean, lux: Float, turnOnLux: Float): Learned {
        val change = when {
            !turnedOn -> -.15f
            lux > turnOnLux -> (.25 * (ln(lux + 1.0) - ln(turnOnLux + 1.0))).toFloat()
            else -> 0f
        }
        return old.copy(shift = (old.shift + change).coerceIn(-1f, 1f))
    }

    fun fromStrength(old: Learned, chosen: Int, expected: Double) =
        old.copy(bias = (old.bias + .2f * (chosen - expected).toFloat()).coerceIn(-3f, 3f))
}
