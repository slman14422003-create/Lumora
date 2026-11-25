package com.lumora.flashlight

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.math.ln

data class Environment(
    val scene: Scene = Scene.UNKNOWN,
    val warmth: Warmth = Warmth.UNKNOWN,
    val flicker: Flicker = Flicker.UNKNOWN,
    @StringRes val hint: Int? = null,
    val cameraActive: Boolean = false
)

enum class CalibrationStatus { SAVED, NO_READING, TOO_DIM, NO_SOURCE, COVERED, UNSTABLE }

class CalibrationOutcome(val status: CalibrationStatus, val lux: Float = 0f)

class AutoSensorHost(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val listener: Listener
) : SensorEventListener {

    fun interface Listener {
        fun onState(luxSmoothed: Float?, strength: Int, reason: Reason, sourcesAvailable: Sources)
    }

    private val sensors = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val light = sensors.getDefaultSensor(Sensor.TYPE_LIGHT)
    private val proximity = sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    private val motion = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        ?: sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    val sources = Sources(light != null, proximity != null, motion != null, Build.VERSION.SDK_INT >= 29)

    private val controller = AutoLightController(sources)
    private val handler = Handler(Looper.getMainLooper())
    private var enabled = false
    private var active = true
    private var screenOn = power.isInteractive
    private var sampling = false
    private var lightRegistered = false
    private var thermalListener: Any? = null
    private val analyzer = AmbientAnalyzer()
    private var foreground = false
    private var checking = false
    private var cameraBlockedForSession = false
    private var cameraLux: Float? = null
    private var cameraReadAt = 0L
    private var warmth = Warmth.UNKNOWN
    private var flicker = Flicker.UNKNOWN
    private var lastAutoLux: Float? = null
    private var lastAutoAt = 0L
    private var manualChoice: Int? = null
    private val burstTimes = mutableListOf<Long>()
    private val burstValues = mutableListOf<Float>()
    private var bgMode = false
    private var released = false
    private var lightUsable = true
    private var lightRegisteredAt = 0L
    private var lightEventAt = 0L
    private var learnedPeriod: String? = null
    private var learnedCache = Learned()
    private var calibrationSink: ((Float) -> Unit)? = null
    private var calibrationListener: SensorEventListener? = null
    private var calibrationEnd: Runnable? = null

    var environment = Environment()
        private set

    var cameraEnabled = prefs.getBoolean(KEY_CAMERA, false) && hasCameraPermission(context)
        set(value) {
            field = value
            cameraBlockedForSession = false
            prefs.edit().putBoolean(KEY_CAMERA, value).apply()
            if (!value) {
                cameraLux = null
                warmth = Warmth.UNKNOWN
            }
            updateCamera()
        }

    private val front = FrontLightSampler(context) { lux, red, blue ->
        cameraLux = lux
        cameraReadAt = now()
        warmth = analyzer.warmth(red, blue)
        calibrationSink?.invoke(lux)
        if (light == null || !lightUsable) controller.onLux(lux)
    }

    private val burstListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent?) {
            if (event == null || burstTimes.size >= 600) return
            burstTimes.add(event.timestamp)
            burstValues.add(event.values[0])
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val endBurst = Runnable {
        sensors.unregisterListener(burstListener)
        flicker = analyzer.flicker(burstTimes.toLongArray(), burstValues.toFloatArray())
        controller.flickering = flicker == Flicker.HZ_50 || flicker == Flicker.HZ_60
    }

    private val startBurst = object : Runnable {
        override fun run() {
            if (!sampling) return
            val sensor = light ?: return
            burstTimes.clear()
            burstValues.clear()
            try {
                sensors.registerListener(burstListener, sensor, 2500)
                handler.postDelayed(endBurst, 700)
            } catch (_: SecurityException) { }
            handler.postDelayed(this, 30_000)
        }
    }

    var preset: Preset
        get() = controller.preset
        set(value) {
            controller.preset = value
            prefs.edit().putString(KEY_PRESET, value.name).apply()
        }

    var sensitivity: Sensitivity
        get() = controller.sensitivity
        set(value) {
            controller.sensitivity = value
            prefs.edit().putString(KEY_SENSITIVITY, value.name).apply()
        }

    private val ticker = object : Runnable {
        override fun run() {
            if (!sampling) return
            analyze()
            val output = controller.tick(now())
            setLightRegistered(output.lightSensorNeeded)
            publish(output)
            if (sampling) handler.postDelayed(this, output.nextTickMs)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            screenOn = intent?.action != Intent.ACTION_SCREEN_OFF
            update()
        }
    }

    init {
        controller.preset = Preset.entries.firstOrNull { it.name == prefs.getString(KEY_PRESET, null) }
            ?: Preset.BALANCED
        controller.sensitivity = Sensitivity.entries
            .firstOrNull { it.name == prefs.getString(KEY_SENSITIVITY, null) } ?: Sensitivity.NORMAL
        val saved = Calibration(prefs.getFloat(KEY_DARK, 10f), prefs.getFloat(KEY_BRIGHT, 220f))
        if (saved.valid) controller.calibration = saved
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        ContextCompat.registerReceiver(context, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    fun start(torchOn: Boolean, strength: Int) {
        enabled = true
        screenOn = power.isInteractive
        commitManualChoice()
        cameraBlockedForSession = false
        controller.closeCap = 10
        controller.start(torchOn, strength, now())
        analyzer.reset()
        environment = Environment()
        update()
    }

    fun stop() {
        if (enabled) {
            lastAutoLux = controller.adjustedLux()
            lastAutoAt = now()
        }
        enabled = false
        update()
    }

    fun setActive(value: Boolean) {
        active = value
        if (value) screenOn = power.isInteractive
        update()
    }

    fun release() {
        if (released) return
        released = true
        cancelCalibration()
        stop()
        try { context.unregisterReceiver(screenReceiver) } catch (_: IllegalArgumentException) { }
    }

    fun setBackgroundMode(value: Boolean) {
        bgMode = value
        update()
    }

    fun canSense() = (light != null && lightUsable) || cameraEnabled

    fun syncTorch(torchOn: Boolean) = controller.syncTorch(torchOn)

    fun userStrength(strength: Int) {
        if (!enabled) return
        controller.userStrength(strength, now())
        publishNow()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (!value) commitManualChoice()
        updateCamera()
    }

    fun setChecking(value: Boolean) {
        checking = value
        updateCamera()
    }

    fun cameraConflict() {
        if (!front.running) return
        cameraBlockedForSession = true
        updateCamera()
    }

    fun setCloseCap(cap: Int) {
        controller.closeCap = cap
    }

    fun learnToggle(turnedOn: Boolean) {
        if (!enabled) return
        val lux = controller.adjustedLux() ?: return
        save(OverrideLearner.fromToggle(controller.learned, turnedOn, lux, controller.turnOnThreshold()),
            lux, if (turnedOn) 10 else 0)
    }

    fun learnManualStrength(strength: Int) {
        if (!enabled && lastAutoLux != null && now() - lastAutoAt <= 60_000) manualChoice = strength
    }

    private fun commitManualChoice() {
        val lux = lastAutoLux ?: return
        val strength = manualChoice ?: return
        manualChoice = null
        lastAutoLux = null
        controller.learned = loadLearned()
        save(OverrideLearner.fromStrength(controller.learned, strength, controller.targetFor(lux.toDouble())),
            lux, strength)
    }

    fun resetLearning() {
        controller.learned = Learned()
        controller.calibration = Calibration()
        manualChoice = null
        learnedPeriod = null
        prefs.edit().remove(KEY_DARK).remove(KEY_BRIGHT).remove(KEY_LOG).also { editor ->
            listOf("day", "night").forEach { editor.remove("shift_$it").remove("bias_$it") }
        }.apply()
    }

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)

    private fun period() = if (hour() in 7..18) "day" else "night"

    private fun loadLearned(): Learned {
        val current = period()
        if (current == learnedPeriod) return learnedCache
        learnedPeriod = current
        learnedCache = Learned(prefs.getFloat("shift_$current", 0f), prefs.getFloat("bias_$current", 0f))
        return learnedCache
    }

    private fun save(learned: Learned, lux: Float, strength: Int) {
        controller.learned = learned
        learnedPeriod = period()
        learnedCache = learned
        val entries = prefs.getString(KEY_LOG, "").orEmpty().split(';').filter { it.isNotEmpty() }
        val log = (entries + "${lux.toInt()},${hour()},$strength").takeLast(50).joinToString(";")
        prefs.edit()
            .putFloat("shift_${period()}", learned.shift)
            .putFloat("bias_${period()}", learned.bias)
            .putString(KEY_LOG, log)
            .apply()
    }

    private fun analyze() {
        val now = now()
        controller.learned = loadLearned()
        val dead = light != null && lightRegistered && lightEventAt == 0L &&
            now - lightRegisteredAt > DEAD_SENSOR_MS
        lightUsable = !dead
        val noLight = light == null || dead
        controller.lightFailed = dead
        controller.cameraFallback = noLight && cameraEnabled
        val seen = cameraLux?.takeIf { front.running && now - cameraReadAt < 3000 }
        if (noLight && seen != null) controller.onLux(seen)
        val lux = controller.lux()
        val scene = analyzer.classify(lux, controller.isCovered, hour())
        val blocked = !noLight && analyzer.blocked(lux, seen)
        val stuck = analyzer.stuckAtZero(lux, controller.isMoving(now), now)
        controller.inhibit = when {
            blocked -> Reason.BLOCKED
            scene == Scene.DAYLIGHT || scene == Scene.DIRECT_SUN -> Reason.SUNLIGHT
            else -> null
        }
        val hint = when {
            dead && !cameraEnabled -> R.string.hint_sensor_dead
            blocked -> R.string.hint_blocked
            stuck -> R.string.hint_stuck
            else -> null
        }
        environment = Environment(scene, warmth, flicker, hint, front.running)
    }

    private fun updateCamera() {
        if (sampling && foreground && cameraEnabled && !checking && !cameraBlockedForSession) front.start()
        else front.stop()
    }

    fun userTorch(torchOn: Boolean) {
        if (!enabled) return
        controller.userTorch(torchOn, now())
        publishNow()
    }

    fun calibrateNow(bright: Boolean, done: (CalibrationOutcome) -> Unit) {
        cancelCalibration()
        controller.learned = loadLearned()
        if (controller.isCovered) return done(CalibrationOutcome(CalibrationStatus.COVERED))
        val sensor = light?.takeIf { lightUsable }
        val camera = sensor == null && cameraEnabled && hasCameraPermission(context)
        if (sensor == null && !camera) return done(CalibrationOutcome(CalibrationStatus.NO_SOURCE))
        val samples = ArrayList<Float>()
        calibrationSink = { samples.add(it) }
        if (sensor != null) {
            val sampler = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent?) {
                    if (event != null) calibrationSink?.invoke(maxOf(0f, event.values[0]))
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            calibrationListener = sampler
            try {
                sensors.registerListener(sampler, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            } catch (_: SecurityException) {
                cancelCalibration()
                return done(CalibrationOutcome(CalibrationStatus.NO_SOURCE))
            }
        } else if (!front.running) {
            front.start()
        }
        val finish = Runnable {
            val result = measure(samples, bright, camera)
            cancelCalibration()
            done(result)
        }
        calibrationEnd = finish
        handler.postDelayed(finish, if (camera) CAMERA_CALIBRATION_MS else SENSOR_CALIBRATION_MS)
    }

    private fun cancelCalibration() {
        calibrationEnd?.let { handler.removeCallbacks(it) }
        calibrationEnd = null
        calibrationListener?.let { sensors.unregisterListener(it) }
        calibrationListener = null
        if (calibrationSink != null) {
            calibrationSink = null
            updateCamera()
        }
    }

    private fun measure(samples: List<Float>, bright: Boolean, camera: Boolean): CalibrationOutcome {
        if (samples.size < (if (camera) 2 else 1)) return CalibrationOutcome(CalibrationStatus.NO_READING)
        if (controller.isCovered) return CalibrationOutcome(CalibrationStatus.COVERED)
        val sorted = samples.sorted()
        if (sorted.size >= 3 && ln(sorted.last() + 1.0) - ln(sorted.first() + 1.0) > ln(4.0)) {
            return CalibrationOutcome(CalibrationStatus.UNSTABLE)
        }
        val median = sorted[sorted.size / 2]
        val result = controller.calibrateAt(controller.adjust(median), bright)
            ?: return CalibrationOutcome(CalibrationStatus.TOO_DIM)
        prefs.edit().putFloat(KEY_DARK, result.darkLux).putFloat(KEY_BRIGHT, result.brightLux).apply()
        return CalibrationOutcome(CalibrationStatus.SAVED, median)
    }

    private fun update() {
        val shouldSample = enabled && active && (screenOn || bgMode)
        if (shouldSample != sampling) {
            sampling = shouldSample
            if (shouldSample) startSensors() else stopSensors()
        }
        updateCamera()
    }

    private fun startSensors() {
        controller.restartSampling(now())
        setLightRegistered(true)
        proximity?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        motion?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        if (Build.VERSION.SDK_INT >= 29) startThermal()
        handler.post(ticker)
        handler.post(startBurst)
    }

    private fun stopSensors() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(startBurst)
        handler.removeCallbacks(endBurst)
        sensors.unregisterListener(burstListener)
        cameraLux = null
        sensors.unregisterListener(this)
        lightRegistered = false
        if (Build.VERSION.SDK_INT >= 29) stopThermal()
    }

    private fun setLightRegistered(value: Boolean) {
        val sensor = light ?: return
        if (value == lightRegistered) return
        if (value) {
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            lightRegisteredAt = now()
        } else {
            sensors.unregisterListener(this, sensor)
        }
        lightRegistered = value
    }

    @androidx.annotation.RequiresApi(29)
    private fun startThermal() {
        val callback = PowerManager.OnThermalStatusChangedListener {
            controller.onThermal(it)
            publishNow()
        }
        controller.onThermal(power.currentThermalStatus)
        power.addThermalStatusListener(ContextCompat.getMainExecutor(context), callback)
        thermalListener = callback
    }

    @androidx.annotation.RequiresApi(29)
    private fun stopThermal() {
        (thermalListener as? PowerManager.OnThermalStatusChangedListener)
            ?.let { power.removeThermalStatusListener(it) }
        thermalListener = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!sampling || event == null) return
        val values = event.values
        when (event.sensor.type) {
            Sensor.TYPE_LIGHT -> {
                lightEventAt = now()
                lightUsable = true
                controller.onLux(values[0])
            }
            Sensor.TYPE_PROXIMITY ->
                controller.onProximity(values[0] < minOf(event.sensor.maximumRange, 5f), now())
            Sensor.TYPE_ACCELEROMETER ->
                controller.onAccelerometer(values[0], values[1], values[2], now())
            Sensor.TYPE_GYROSCOPE ->
                controller.onGyroscope(values[0], values[1], values[2], now())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun publishNow() {
        if (enabled) publish(controller.output(now()))
    }

    private fun publish(output: Output) =
        listener.onState(output.lux, output.strength, output.reason, sources)

    private fun now() = SystemClock.elapsedRealtime()

    private companion object {
        const val DEAD_SENSOR_MS = 6000L
        const val SENSOR_CALIBRATION_MS = 2500L
        const val CAMERA_CALIBRATION_MS = 4000L
        const val KEY_CAMERA = "camera_sensing"
        const val KEY_LOG = "override_log"
        const val KEY_PRESET = "auto_preset"
        const val KEY_SENSITIVITY = "auto_sensitivity"
        const val KEY_DARK = "auto_calibration_dark"
        const val KEY_BRIGHT = "auto_calibration_bright"
    }
}
