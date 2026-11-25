package com.lumora.flashlight

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sqrt

enum class Preset(val min: Int, val max: Int) { ECO(2, 6), BALANCED(4, 10), MAX(8, 10) }

enum class Sensitivity(val factor: Double) { LOW(.5), NORMAL(1.0), HIGH(2.0) }

enum class Reason {
    STARTING, DARK, DIM, BRIGHT, WAITING, COVERED, USER_OVERRIDE, THERMAL, PAUSED, NO_LIGHT_SENSOR,
    SUNLIGHT, BLOCKED, CLOSE, FACE_DOWN
}

data class Sources(
    val light: Boolean,
    val proximity: Boolean,
    val motion: Boolean,
    val thermal: Boolean
)

data class Calibration(val darkLux: Float = 10f, val brightLux: Float = 220f) {
    val valid get() = darkLux >= 0f && brightLux >= maxOf(darkLux * 3f, darkLux + 30f)
}

data class Output(
    val lux: Float?,
    val strength: Int,
    val reason: Reason,
    val lightSensorNeeded: Boolean,
    val nextTickMs: Long
)

fun reasonLine(text: TextSource, reason: Reason, strength: Int, dimmable: Boolean): String {
    val result = when {
        strength == 0 -> text.get(R.string.result_off)
        dimmable -> text.get(R.string.result_percent, strength * 10)
        else -> text.get(R.string.result_on)
    }
    return when (reason) {
        Reason.STARTING -> text.get(R.string.reason_starting)
        Reason.DARK -> text.get(R.string.reason_dark, result)
        Reason.DIM -> text.get(R.string.reason_dim, result)
        Reason.BRIGHT -> text.get(R.string.reason_bright, result)
        Reason.WAITING -> text.get(R.string.reason_waiting)
        Reason.COVERED ->
            if (strength == 0) text.get(R.string.reason_covered_paused)
            else text.get(R.string.reason_covered_holding, result)
        Reason.USER_OVERRIDE -> text.get(R.string.reason_override, result)
        Reason.THERMAL -> text.get(R.string.reason_thermal, result)
        Reason.PAUSED -> text.get(R.string.reason_paused, result)
        Reason.NO_LIGHT_SENSOR -> text.get(R.string.reason_no_sensor)
        Reason.SUNLIGHT -> text.get(R.string.reason_sunlight)
        Reason.BLOCKED -> text.get(R.string.reason_blocked)
        Reason.CLOSE -> text.get(R.string.reason_close, result)
        Reason.FACE_DOWN -> text.get(R.string.reason_face_down, result)
    }
}

class AutoLightController(private val sources: Sources) {
    var preset = Preset.BALANCED
    var sensitivity = Sensitivity.NORMAL
    var calibration = Calibration()
    var learned = Learned()
    var inhibit: Reason? = null
    var flickering = false
    var cameraFallback = false
    var lightFailed = false
    var closeCap = 10

    private enum class Change { TURN_ON, TURN_OFF, UP, DOWN }

    private val window = ArrayDeque<Float>()
    private var latestRaw: Float? = null
    private var smoothed: Double? = null
    private var lastSampleAt = 0L
    private var on = false
    private var level = 10
    private var pending: Change? = null
    private var pendingSince = 0L
    private var overrideUntil = 0L
    private var covered = false
    private var thermalStatus = 0
    private var lastMotionAt = 0L
    private var lastLightChangeAt = 0L
    private var reference = 0.0
    private var paused = false
    private var peekUntil = 0L
    private var nextPeekAt = 0L
    private var lastAccel: FloatArray? = null
    private var sensedMotionAt: Long? = null
    private var faceDown = false
    private var faceDownSince = 0L

    val isCovered get() = covered

    fun isMoving(now: Long) = sensedMotionAt?.let { now - it < 2000L } == true

    fun lux() = currentLux()

    fun adjustedLux() = effectiveLux()?.toFloat()

    fun turnOnThreshold() = turnOnLux().toFloat()

    fun start(torchOn: Boolean, strength: Int, now: Long) {
        on = torchOn
        level = strength.coerceIn(1, 10)
        overrideUntil = 0L
        restartSampling(now)
    }

    fun restartSampling(now: Long) {
        resetFilter()
        pending = null
        paused = false
        peekUntil = 0L
        lastAccel = null
        lastMotionAt = now
        lastLightChangeAt = now
        faceDown = false
        faceDownSince = 0L
    }

    private fun resetFilter() {
        window.clear()
        latestRaw = null
        smoothed = null
    }

    fun onLux(lux: Float) {
        if (!lux.isNaN() && !lux.isInfinite()) latestRaw = maxOf(0f, lux)
    }

    fun onProximity(isCovered: Boolean, now: Long) {
        if (covered == isCovered) return
        covered = isCovered
        pending = null
        if (!isCovered) resetFilter()
        wake(now)
    }

    fun onThermal(status: Int) {
        thermalStatus = status
    }

    fun onAccelerometer(x: Float, y: Float, z: Float, now: Long) {
        faceDown = z < -FACE_DOWN_Z
        if (!faceDown) faceDownSince = 0L else if (faceDownSince == 0L) faceDownSince = now
        val previous = lastAccel
        if (previous == null) {
            lastAccel = floatArrayOf(x, y, z)
            return
        }
        if (magnitude(x - previous[0], y - previous[1], z - previous[2]) > MOTION_ACCEL) {
            lastAccel = floatArrayOf(x, y, z)
            sensedMotionAt = now
            wake(now)
        }
    }

    fun onGyroscope(x: Float, y: Float, z: Float, now: Long) {
        if (magnitude(x, y, z) > MOTION_GYRO) {
            sensedMotionAt = now
            wake(now)
        }
    }

    private fun magnitude(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

    private fun wake(now: Long) {
        lastMotionAt = now
        paused = false
        peekUntil = 0L
    }

    fun syncTorch(torchOn: Boolean) {
        on = torchOn
        pending = null
    }

    fun userStrength(strength: Int, now: Long) {
        level = strength.coerceIn(1, 10)
        holdForUser(now)
    }

    fun userTorch(torchOn: Boolean, now: Long) {
        on = torchOn
        holdForUser(now)
    }

    private fun holdForUser(now: Long) {
        overrideUntil = now + OVERRIDE_MS
        pending = null
        wake(now)
    }

    fun adjust(lux: Float): Float =
        (lux / (sensitivity.factor * exp(learned.shift.toDouble()))).toFloat()

    fun calibrate(bright: Boolean): Calibration? = currentLux()?.let { calibrateAt(it, bright) }

    fun calibrateAt(lux: Float, bright: Boolean): Calibration? {
        if (lux.isNaN() || lux.isInfinite() || lux < 0f) return null
        val current = calibration
        val next = if (bright) {
            val dark = minOf(current.darkLux, lux / 3.2f, lux - 31f).coerceAtLeast(0f)
            Calibration(dark, lux)
        } else {
            Calibration(lux, maxOf(current.brightLux, lux * 3f, lux + 30f))
        }
        if (!next.valid) return null
        calibration = next
        pending = null
        return next
    }

    fun tick(now: Long): Output {
        if (paused) tickPaused(now) else tickActive(now)
        return output(now)
    }

    private fun tickPaused(now: Long) {
        if (peekUntil == 0L) {
            if (now >= nextPeekAt) {
                latestRaw = null
                peekUntil = now + PEEK_MS
            }
            return
        }
        if (now < peekUntil) return
        val raw = latestRaw
        val old = smoothed
        if (raw != null && old != null && abs(ln(raw + 1.0) - old) > RESUME_DELTA) {
            resetFilter()
            latestRaw = raw
            wake(now)
            lastLightChangeAt = now
        } else {
            peekUntil = 0L
            nextPeekAt = now + PEEK_EVERY_MS
        }
    }

    private fun tickActive(now: Long) {
        val raw = latestRaw ?: return
        window.addLast(raw)
        if (window.size > WINDOW) window.removeFirst()
        val median = window.sorted()[window.size / 2]
        val value = ln(median + 1.0)
        val old = smoothed
        smoothed = if (old == null) {
            value
        } else {
            val alpha = 1.0 - exp(-(now - lastSampleAt) / (if (flickering) SMOOTHING_MS * 2 else SMOOTHING_MS))
            old + alpha * (value - old)
        }
        lastSampleAt = now

        val current = smoothed ?: return
        if (old == null || abs(current - reference) > CHANGE_DELTA) {
            reference = current
            lastLightChangeAt = now
        }
        if (window.size >= MIN_SAMPLES) evaluate(now)

        if (sources.motion && sources.light && pending == null && now >= overrideUntil &&
            now - lastMotionAt >= STILL_MS && now - lastLightChangeAt >= STILL_MS
        ) {
            paused = true
            peekUntil = 0L
            nextPeekAt = now + PEEK_EVERY_MS
        }
    }

    private fun evaluate(now: Long) {
        if (now < overrideUntil) {
            pending = null
            return
        }
        val lux = effectiveLux() ?: return
        val target = targetFor(lux)
        val laidFaceDown = faceDown && faceDownSince != 0L && now - faceDownSince >= FACE_DOWN_MS
        val change = when {
            on && laidFaceDown -> Change.TURN_OFF
            on && lux >= offLux() -> Change.TURN_OFF
            on && target - level >= STRENGTH_HYSTERESIS && !covered -> Change.UP
            on && level - target >= STRENGTH_HYSTERESIS -> Change.DOWN
            !on && lux <= turnOnLux() && !covered && !faceDown && inhibit == null -> Change.TURN_ON
            else -> null
        }
        if (change != pending) {
            pending = change
            pendingSince = now
            return
        }
        if (change == null || now - pendingSince < DWELL_MS) return
        when (change) {
            Change.TURN_ON -> {
                on = true
                level = target.roundToInt()
            }
            Change.TURN_OFF -> on = false
            Change.UP, Change.DOWN -> level = target.roundToInt()
        }
        pending = null
    }

    fun targetFor(lux: Double): Double {
        val low = ln(calibration.darkLux + 1.0)
        val high = ln(calibration.brightLux + 1.0)
        val position = ((ln(lux + 1.0) - low) / (high - low)).coerceIn(0.0, 1.0)
        return (preset.max - position * (preset.max - preset.min) + learned.bias).coerceIn(1.0, 10.0)
    }

    // An ordinary lit room (about 50 lux) already means the flashlight isn't needed.
    private fun offLux(): Double =
        minOf(calibration.brightLux.toDouble(), maxOf(INDOOR_OFF_LUX, turnOnLux() * 1.25))

    private fun turnOnLux(): Double {
        val low = ln(calibration.darkLux + 1.0)
        val high = ln(calibration.brightLux + 1.0)
        return exp(low + TURN_ON_POSITION * (high - low)) - 1.0
    }

    private fun currentLux(): Float? = smoothed?.let { (exp(it) - 1.0).toFloat() }

    private fun effectiveLux(): Double? = smoothed?.let { (exp(it) - 1.0) / (sensitivity.factor * exp(learned.shift.toDouble())) }

    private fun thermalCap() = when {
        thermalStatus >= 4 -> 2
        thermalStatus == 3 -> 4
        thermalStatus == 2 -> 7
        else -> 10
    }

    fun output(now: Long): Output {
        val cap = thermalCap()
        val strength = if (on) minOf(level, cap, closeCap) else 0
        val lux = effectiveLux()
        val reason = when {
            (!sources.light || lightFailed) && !cameraFallback -> Reason.NO_LIGHT_SENSOR
            on && cap < level -> Reason.THERMAL
            now < overrideUntil -> Reason.USER_OVERRIDE
            faceDown && now - faceDownSince >= FACE_DOWN_MS -> Reason.FACE_DOWN
            covered -> Reason.COVERED
            !on && inhibit != null -> inhibit ?: Reason.BRIGHT
            on && closeCap < level -> Reason.CLOSE
            paused -> Reason.PAUSED
            lux == null -> Reason.STARTING
            pending != null -> Reason.WAITING
            lux >= offLux() -> Reason.BRIGHT
            lux <= turnOnLux() -> Reason.DARK
            else -> Reason.DIM
        }
        val settling = smoothed == null || pending != null || now - lastLightChangeAt < SETTLE_MS
        val interval = when {
            paused && peekUntil == 0L -> SLOW_TICK_MS
            paused || settling -> FAST_TICK_MS
            else -> SLOW_TICK_MS
        }
        return Output(currentLux(), strength, reason, !paused || peekUntil != 0L, interval)
    }

    companion object {
        const val DWELL_MS = 2500L
        const val OVERRIDE_MS = 120_000L
        const val STILL_MS = 10_000L
        const val PEEK_EVERY_MS = 15_000L
        const val PEEK_MS = 1500L
        private const val INDOOR_OFF_LUX = 50.0
        private const val WINDOW = 5
        private const val FACE_DOWN_Z = 7f
        private const val FACE_DOWN_MS = 1500L
        private const val MIN_SAMPLES = 3
        private const val SMOOTHING_MS = 800.0
        private const val STRENGTH_HYSTERESIS = .75
        private const val TURN_ON_POSITION = .4
        private const val CHANGE_DELTA = .15
        private const val RESUME_DELTA = .25
        private const val MOTION_ACCEL = .6f
        private const val MOTION_GYRO = .2f
        private const val SETTLE_MS = 3000L
        private const val FAST_TICK_MS = 250L
        private const val SLOW_TICK_MS = 750L
    }
}
