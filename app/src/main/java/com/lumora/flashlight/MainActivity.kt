package com.lumora.flashlight

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.widget.Toast
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlin.math.roundToInt

class MainActivity : Activity(), MainScreen.Listener {
    private lateinit var cameraManager: CameraManager
    private lateinit var auto: AutoSensorHost
    private var torchCameraId: String? = null
    private var torchOn = false
    private var autoMode = false
    private var strengthSupported = false
    private var maxStrengthLevel = 1
    private var keepOnInBackground = true
    private var inForeground = false
    private var torchCallback: CameraManager.TorchCallback? = null
    private lateinit var prefs: SharedPreferences
    private var lastStrength = -1

    private lateinit var screen: MainScreen
    private var strength = 10
    private var checking = false
    private var closeCap = 10
    private val analyzer = AmbientAnalyzer()
    private val preCheck by lazy { BackPreCheck(this) }

    private var lightMode = LightMode.STEADY
    private var timerMinutes = 0
    private var screenFallback = true
    private var timerActiveHere = false

    private val handler = Handler(Looper.getMainLooper())
    private var patternRunning = false
    private var patternSteps: List<Step> = emptyList()
    private var patternIndex = 0
    private var ignoreTorchUntil = 0L
    private val patternTick = Runnable { stepPattern() }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen().setOnExitAnimationListener { splash ->
            splash.iconView.animate().scaleX(1.25f).scaleY(1.25f)
                .setDuration(260).setInterpolator(AccelerateInterpolator()).start()
            splash.view.animate().alpha(0f).setDuration(260)
                .withEndAction { splash.remove() }.start()
        }
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(Prefs.FILE, MODE_PRIVATE)
        keepOnInBackground = prefs.getBoolean(Prefs.KEEP_ON, true)
        timerMinutes = prefs.getInt(Prefs.TIMER_MINUTES, 0)
        screenFallback = prefs.getBoolean(Prefs.SCREEN_FALLBACK, true)
        setupWindow()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        auto = AutoSensorHost(this, prefs, ::onAutoState)
        torchCameraId = TorchSupport.findTorchCamera(cameraManager)
        detectStrengthSupport()

        screen = MainScreen(
            this, prefs.getBoolean(Prefs.AMOLED, false), keepOnInBackground, screenFallback, this
        )
        screen.preset = auto.preset
        screen.sensitivity = auto.sensitivity
        screen.cameraEnabled = auto.cameraEnabled
        setContentView(screen.root)
        AutoOffTimer.onExpired = { onTimerExpired() }
        registerTorchCallback()
        refreshUi()
    }

    // Edge-to-edge is enforced on targetSdk 35+, so bar colors are ignored there.
    // The dark window background shows behind the bars; insets are applied in MainScreen.
    @Suppress("DEPRECATION")
    private fun setupWindow() {
        if (Build.VERSION.SDK_INT >= 30) window.setDecorFitsSystemWindows(false)
        if (Build.VERSION.SDK_INT < 35) {
            val barColor = getColor(
                if (Build.VERSION.SDK_INT >= 30) R.color.lumora_transparent else R.color.lumora_night
            )
            window.statusBarColor = barColor
            window.navigationBarColor = barColor
        }
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
    }

    private fun detectStrengthSupport() {
        strengthSupported = false
        maxStrengthLevel = 1
        if (Build.VERSION.SDK_INT < 33) return
        val id = torchCameraId ?: return
        try {
            val max = cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
            maxStrengthLevel = max
            strengthSupported = max > 1
        } catch (_: Exception) { }
    }

    // Keeps the UI in sync when the torch is changed elsewhere (Quick Settings, another app).
    private fun registerTorchCallback() {
        if (torchCameraId == null) return
        val callback = object : CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (checking || patternRunning || cameraId != torchCameraId || torchOn == enabled) return
                if (SystemClock.uptimeMillis() < ignoreTorchUntil) return
                torchOn = enabled
                if (!enabled) lastStrength = -1
                refreshUi()
                if (autoMode) auto.userTorch(enabled)
            }

            override fun onTorchModeUnavailable(cameraId: String) {
                if (checking || cameraId != torchCameraId) return
                auto.cameraConflict()
                if (patternRunning) stopPattern()
                if (!torchOn) return
                torchOn = false
                lastStrength = -1
                refreshUi()
                if (autoMode) auto.userTorch(false)
            }
        }
        try {
            cameraManager.registerTorchCallback(callback, Handler(Looper.getMainLooper()))
            torchCallback = callback
        } catch (_: Exception) { }
    }

    override fun onPowerClick() = toggleTorch()

    override fun onManualClick() {
        autoMode = false
        auto.stop()
        refreshUi()
    }

    override fun onAutoClick() = enableAutoMode()

    override fun onStrengthChanged(strength: Int, fromUser: Boolean) {
        if (!fromUser) return
        this.strength = strength
        closeCap = 10
        auto.setCloseCap(10)
        if (!autoMode) auto.learnManualStrength(strength)
        if (torchOn) applyTorch()
        refreshUi()
        if (autoMode) auto.userStrength(strength)
    }

    override fun onPresetSelected(preset: Preset) {
        auto.preset = preset
    }

    override fun onSensitivitySelected(sensitivity: Sensitivity) {
        auto.sensitivity = sensitivity
    }

    override fun onCalibrate(bright: Boolean) {
        val message = getString(
            if (auto.calibrate(bright)) R.string.toast_calibrated else R.string.toast_calibration_failed
        )
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onCameraToggled(checked: Boolean) {
        if (checked && !hasCameraPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_REQUEST)
        } else {
            auto.cameraEnabled = checked
            screen.cameraEnabled = checked
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != CAMERA_REQUEST) return
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        auto.cameraEnabled = granted
        screen.cameraEnabled = granted
        val message = getString(if (granted) R.string.toast_camera_on else R.string.toast_camera_denied)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onResetLearning() {
        auto.resetLearning()
        Toast.makeText(this, R.string.toast_learning_cleared, Toast.LENGTH_SHORT).show()
    }

    override fun onReadingMode() {
        if (torchCameraId == null || !strengthSupported) {
            screen.showScreenLight(false, timerMinutes)
            return
        }
        if (autoMode) onManualClick()
        if (lightMode != LightMode.STEADY) {
            lightMode = LightMode.STEADY
            stopPattern()
        }
        strength = READING_STRENGTH
        val wasOn = torchOn
        torchOn = true
        if (wasOn) applyTorch() else turnOnChecked()
        refreshUi()
    }

    override fun onLightModeSelected(mode: LightMode) {
        if (mode == lightMode) return
        lightMode = mode
        if (mode != LightMode.STEADY && autoMode) {
            autoMode = false
            auto.stop()
        }
        stopPattern()
        if (torchOn) applyTorch()
        refreshUi()
    }

    override fun onTimerSelected(minutes: Int) {
        timerMinutes = minutes
        prefs.edit().putInt(Prefs.TIMER_MINUTES, minutes).apply()
        AutoOffTimer.cancel(this)
        refreshUi()
    }

    override fun onScreenFallbackChanged(checked: Boolean) {
        screenFallback = checked
        prefs.edit().putBoolean(Prefs.SCREEN_FALLBACK, checked).apply()
    }

    override fun onOpenScreenLight() {
        screen.showScreenLight(true, timerMinutes)
    }

    private fun turnOnChecked() {
        if (!auto.cameraEnabled || !hasCameraPermission(this) || checking) {
            applyTorch()
            return
        }
        checking = true
        auto.setChecking(true)
        preCheck.run { distance ->
            checking = false
            if (isDestroyed) return@run
            auto.setChecking(false)
            closeCap = analyzer.closeSubjectCap(distance)
            auto.setCloseCap(closeCap)
            if (torchOn) applyTorch()
        }
    }

    override fun onKeepOnChanged(checked: Boolean) {
        keepOnInBackground = checked
        prefs.edit().putBoolean(Prefs.KEEP_ON, checked).apply()
    }

    override fun onAmoledChanged(checked: Boolean) {
        prefs.edit().putBoolean(Prefs.AMOLED, checked).apply()
    }

    private fun enableAutoMode() {
        if (!auto.sources.light && !auto.cameraEnabled) {
            Toast.makeText(this, R.string.toast_no_light_sensor, Toast.LENGTH_LONG).show()
            return
        }
        if (autoMode) return
        if (lightMode != LightMode.STEADY) {
            lightMode = LightMode.STEADY
            stopPattern()
            if (torchOn) applyTorch()
        }
        autoMode = true
        refreshUi()
        auto.start(torchOn, strength)
    }

    private fun toggleTorch() {
        if (torchCameraId == null) {
            if (screenFallback) {
                screen.showScreenLight(true, timerMinutes)
            } else {
                Toast.makeText(this, R.string.toast_no_flash, Toast.LENGTH_LONG).show()
            }
            return
        }
        torchOn = !torchOn
        if (autoMode) auto.learnToggle(torchOn)
        if (torchOn && lightMode == LightMode.STEADY) turnOnChecked() else applyTorch()
        refreshUi()
        if (autoMode) auto.userTorch(torchOn)
    }

    // Writes the physical torch state. Throws if the camera service refuses.
    private fun writeHardware(on: Boolean) {
        val id = torchCameraId ?: return
        if (!on) {
            cameraManager.setTorchMode(id, false)
            lastStrength = -1
        } else if (strengthSupported && Build.VERSION.SDK_INT >= 33) {
            val level = (((minOf(strength, closeCap) / 10f) * (maxStrengthLevel - 1)).roundToInt() + 1)
                .coerceIn(1, maxStrengthLevel)
            if (lastStrength != level) {
                cameraManager.turnOnTorchWithStrengthLevel(id, level)
                lastStrength = level
            }
        } else {
            cameraManager.setTorchMode(id, true)
        }
    }

    private fun applyTorch() {
        if (torchCameraId == null) return
        if (checking && torchOn) return
        if (torchOn && lightMode != LightMode.STEADY && !autoMode) {
            startPattern()
            refreshUi()
            return
        }
        if (patternRunning) stopPattern()
        if (!torchOn) closeCap = 10
        try {
            writeHardware(torchOn)
        } catch (e: Exception) {
            onFlashFailure(e)
        }
        refreshUi()
    }

    private fun onFlashFailure(e: Exception) {
        stopPattern()
        torchOn = false
        lastStrength = -1
        val reason = e.localizedMessage ?: getString(R.string.toast_device_restriction)
        Toast.makeText(this, getString(R.string.toast_flash_unavailable, reason), Toast.LENGTH_LONG).show()
        if (autoMode) auto.userTorch(false)
    }

    private fun startPattern() {
        if (patternRunning) return
        patternSteps = LightPatterns.steps(lightMode)
        patternIndex = 0
        patternRunning = true
        ignoreTorchUntil = SystemClock.uptimeMillis() + TORCH_SETTLE_MS
        handler.post(patternTick)
    }

    private fun stopPattern() {
        if (!patternRunning) return
        patternRunning = false
        handler.removeCallbacks(patternTick)
        lastStrength = -1
        ignoreTorchUntil = SystemClock.uptimeMillis() + TORCH_SETTLE_MS
    }

    private fun stepPattern() {
        if (!patternRunning || patternSteps.isEmpty()) return
        val step = patternSteps[patternIndex]
        patternIndex = (patternIndex + 1) % patternSteps.size
        try {
            writeHardware(step.on)
        } catch (e: Exception) {
            onFlashFailure(e)
            refreshUi()
            return
        }
        handler.postDelayed(patternTick, step.millis)
    }

    // Starts the auto-off timer when the light comes on and cancels it when the light goes off.
    private fun syncTimer() {
        val wanted = torchOn && !autoMode && timerMinutes > 0
        if (wanted) {
            if (AutoOffTimer.endsAt(this) == 0L) AutoOffTimer.start(this, timerMinutes)
        } else if (timerActiveHere) {
            AutoOffTimer.cancel(this)
        }
        timerActiveHere = wanted
    }

    private fun onTimerExpired() {
        stopPattern()
        torchOn = false
        lastStrength = -1
        closeCap = 10
        timerActiveHere = false
        auto.syncTorch(false)
        refreshUi()
        Toast.makeText(this, R.string.toast_timer_expired, Toast.LENGTH_SHORT).show()
    }

    private fun onAutoState(luxSmoothed: Float?, level: Int, reason: Reason, sources: Sources) {
        if (!autoMode) return
        screen.showAuto(luxSmoothed, level, reason, sources, auto.environment)
        val on = level > 0
        val turningOn = on && !torchOn
        val changed = on != torchOn || (on && level != strength)
        torchOn = on
        if (on) strength = level
        if (turningOn) turnOnChecked() else if (changed) applyTorch()
        if (!inForeground && !torchOn) auto.setActive(false)
    }

    private fun refreshUi() {
        if (!::screen.isInitialized) return
        syncTimer()
        val endsAt = if (torchOn && !autoMode) AutoOffTimer.endsAt(this) else 0L
        screen.render(
            UiState(torchOn, autoMode, strength, strengthSupported, lightMode, timerMinutes, endsAt)
        )
    }

    override fun onResume() {
        super.onResume()
        inForeground = true
        auto.setForeground(true)
        auto.setActive(true)
        refreshUi()
    }

    override fun onPause() {
        super.onPause()
        inForeground = false
        auto.setForeground(false)
        if (patternRunning) {
            // SOS and strobe only run while the app is visible.
            stopPattern()
            try { torchCameraId?.let { cameraManager.setTorchMode(it, false) } } catch (_: Exception) {}
            torchOn = false
            lastStrength = -1
            refreshUi()
        }
        if (keepOnInBackground) {
            // Light stays on; AUTO keeps sensing only while the light is on so it can still switch off.
            if (!(autoMode && torchOn)) auto.setActive(false)
        } else {
            auto.setActive(false)
            if (torchOn) {
                try { torchCameraId?.let { cameraManager.setTorchMode(it, false) } } catch (_: Exception) {}
                torchOn = false
                lastStrength = -1
                auto.syncTorch(false)
                refreshUi()
            }
        }
    }

    override fun onDestroy() {
        AutoOffTimer.onExpired = null
        stopPattern()
        handler.removeCallbacks(patternTick)
        auto.release()
        torchCallback?.let { try { cameraManager.unregisterTorchCallback(it) } catch (_: Exception) {} }
        try { torchCameraId?.let { cameraManager.setTorchMode(it, false) } } catch (_: Exception) {}
        AutoOffTimer.cancel(this)
        super.onDestroy()
    }

    companion object {
        private const val CAMERA_REQUEST = 41
        private const val READING_STRENGTH = 3
        private const val TORCH_SETTLE_MS = 500L
    }
}
