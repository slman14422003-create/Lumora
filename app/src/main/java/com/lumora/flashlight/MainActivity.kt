package com.lumora.flashlight

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import kotlin.math.roundToInt

class MainActivity : Activity(), SensorEventListener {
    private lateinit var cameraManager: CameraManager
    private lateinit var sensorManager: SensorManager
    private var torchCameraId: String? = null
    private var lightSensor: Sensor? = null
    private var torchOn = false
    private var autoMode = false
    private var lux = 0f
    private var hasCameraPermission = false
    private var lastStrength = -1

    private val bg = Color.rgb(6, 10, 20)
    private val panel = Color.rgb(14, 23, 41)
    private val blue = Color.rgb(65, 183, 255)
    private val cyan = Color.rgb(98, 232, 255)
    private val primaryTextColor = Color.rgb(241, 247, 255)
    private val muted = Color.rgb(132, 151, 180)

    private lateinit var powerButton: TextView
    private lateinit var statusText: TextView
    private lateinit var luxText: TextView
    private lateinit var strengthText: TextView
    private lateinit var strengthSlider: SeekBar
    private lateinit var autoButton: TextView
    private lateinit var manualButton: TextView
    private lateinit var glow: View
    private var strength = 10

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        torchCameraId = cameraManager.cameraIdList.firstOrNull { id ->
            cameraManager.getCameraCharacteristics(id)
                .get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        }

        buildInterface()
        refreshUi()
        hasCameraPermission = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (!hasCameraPermission) requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
    }

    private fun buildInterface() {
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(22), dp(22), dp(26))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(13, 27, 52), bg, Color.rgb(5, 8, 16)))
        }
        scroll.addView(page)
        setContentView(scroll)

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val logo = TextView(this).apply {
            text = "✦"
            textSize = 25f
            setTextColor(cyan)
            gravity = Gravity.CENTER
            background = circle(Color.rgb(20, 54, 91), Color.rgb(43, 121, 181))
        }
        header.addView(logo, LinearLayout.LayoutParams(dp(44), dp(44)))
        val brandStack = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        brandStack.addView(label("LUMORA", 20f, primaryTextColor, true, .16f))
        brandStack.addView(label("SMART FLASHLIGHT", 9f, muted, false, .18f))
        header.addView(brandStack, LinearLayout.LayoutParams(0, -2, 1f))
        val version = TextView(this).apply {
            text = "PREMIUM"
            textSize = 9f
            letterSpacing = .12f
            setTextColor(Color.rgb(255, 211, 122))
            setPadding(dp(11), dp(8), dp(11), dp(8))
            background = rounded(Color.rgb(49, 39, 27), 30, Color.rgb(113, 85, 43))
        }
        header.addView(version)
        page.addView(header, match())

        page.addView(label("ILLUMINATE YOUR WORLD", 10f, muted, true, .20f),
            match().apply { topMargin = dp(34) })

        val orb = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(276), dp(276)).apply {
                gravity = Gravity.CENTER
                topMargin = dp(22); bottomMargin = dp(8)
            }
        }
        glow = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(20, 65, 183, 255))
                setStroke(dp(1), Color.argb(100, 65, 183, 255))
            }
        }
        orb.addView(glow, FrameLayout.LayoutParams(-1, -1))
        val innerRing = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(1), Color.argb(90, 98, 232, 255))
            }
        }
        orb.addView(innerRing, FrameLayout.LayoutParams(dp(236), dp(236), Gravity.CENTER))
        powerButton = TextView(this).apply {
            text = "⏻"
            textSize = 70f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(35, 100, 164), Color.rgb(12, 31, 62))).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(73, 188, 255))
            }
            elevation = dp(18).toFloat()
            setOnClickListener { toggleTorch() }
            contentDescription = "Toggle flashlight"
        }
        orb.addView(powerButton, FrameLayout.LayoutParams(dp(190), dp(190), Gravity.CENTER))
        page.addView(orb)

        statusText = label("READY • LIGHT OFF", 11f, cyan, true, .18f).apply { gravity = Gravity.CENTER }
        page.addView(statusText, match())

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(panel, 22, Color.rgb(34, 57, 88))
        }
        page.addView(controls, match().apply { topMargin = dp(30) })

        val strengthRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        strengthText = label("BRIGHTNESS", 10f, muted, true, .13f)
        strengthRow.addView(strengthText, LinearLayout.LayoutParams(0, -2, 1f))
        val strengthValue = label("MAX", 12f, primaryTextColor, true, .06f).also { it.tag = "strength-value" }
        strengthRow.addView(strengthValue)
        controls.addView(strengthRow)

        strengthSlider = SeekBar(this).apply {
            max = 9
            progress = 9
            progressTintList = ColorStateList.valueOf(blue)
            thumbTintList = ColorStateList.valueOf(Color.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(41, 59, 85))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) {
                    strength = p + 1
                    (strengthValue).text = if (strength == 10) "MAX" else "$strength / 10"
                    if (torchOn && !autoMode && fromUser) applyTorch()
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        controls.addView(strengthSlider, match().apply { topMargin = dp(6) })

        page.addView(label("LIGHT CONTROL", 10f, muted, true, .16f),
            match().apply { topMargin = dp(25); bottomMargin = dp(11) })

        val modeRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        manualButton = modeChip("MANUAL")
        autoButton = modeChip("AUTO")
        modeRow.addView(manualButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { rightMargin = dp(6) })
        modeRow.addView(autoButton, LinearLayout.LayoutParams(0, dp(52), 1f).apply { leftMargin = dp(6) })
        page.addView(modeRow, match())
        manualButton.setOnClickListener {
            autoMode = false
            sensorManager.unregisterListener(this)
            refreshUi()
        }
        autoButton.setOnClickListener { enableAutoMode() }

        val sensorCard = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(Color.rgb(11, 19, 34), 17, Color.rgb(31, 48, 72))
        }
        val sensorLabel = label("AMBIENT LIGHT", 10f, muted, true, .13f)
        sensorCard.addView(sensorLabel, LinearLayout.LayoutParams(0, -2, 1f))
        luxText = label("— lux", 14f, primaryTextColor, true, .02f)
        sensorCard.addView(luxText)
        page.addView(sensorCard, match().apply { topMargin = dp(16) })

        val note = label("AUTO MODE USES YOUR AMBIENT LIGHT SENSOR", 9f,
            Color.rgb(91, 117, 151), false, .10f).apply { gravity = Gravity.CENTER }
        page.addView(note, match().apply { topMargin = dp(22) })
    }

    private fun enableAutoMode() {
        if (lightSensor == null) {
            Toast.makeText(this, "This device has no ambient light sensor.", Toast.LENGTH_LONG).show()
            return
        }
        autoMode = true
        sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
        refreshUi()
    }

    private fun toggleTorch() {
        if (!hasCameraPermission) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
            return
        }
        if (torchCameraId == null) {
            Toast.makeText(this, "No flashlight hardware was detected.", Toast.LENGTH_LONG).show()
            return
        }
        torchOn = !torchOn
        applyTorch()
        refreshUi()
    }

    private fun applyTorch() {
        val id = torchCameraId ?: return
        try {
            if (!torchOn) {
                cameraManager.setTorchMode(id, false)
                lastStrength = -1
            } else if (Build.VERSION.SDK_INT >= 33) {
                val max = cameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.FLASH_INFO_STRENGTH_MAXIMUM_LEVEL) ?: 1
                val level = if (max > 1) ((strength / 10f) * (max - 1)).roundToInt() + 1 else 1
                val safeLevel = level.coerceIn(1, max)
                if (lastStrength != safeLevel) {
                    cameraManager.turnOnTorchWithStrengthLevel(id, safeLevel)
                    lastStrength = safeLevel
                }
            } else {
                cameraManager.setTorchMode(id, true)
            }
        } catch (e: Exception) {
            torchOn = false
            Toast.makeText(this, "Flash unavailable: ${e.localizedMessage ?: "device restriction"}", Toast.LENGTH_LONG).show()
        }
        refreshUi()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (!autoMode || event == null) return
        lux = event.values.firstOrNull() ?: return
        luxText.text = "${lux.roundToInt()} lux"
        // Hysteresis prevents flicker when the reading sits near a threshold.
        when {
            lux >= 220f && torchOn -> {
                torchOn = false
                applyTorch()
            }
            lux <= 35f && !torchOn -> {
                strength = 10
                strengthSlider.progress = 9
                torchOn = true
                applyTorch()
            }
            lux in 36f..219f && torchOn -> {
                val next = when {
                    lux < 70f -> 9
                    lux < 120f -> 7
                    else -> 5
                }
                if (next != strength) {
                    strength = next
                    strengthSlider.progress = next - 1
                    applyTorch()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun refreshUi() {
        if (!::statusText.isInitialized) return
        statusText.text = when {
            autoMode && torchOn -> "AUTO • LIGHT ON"
            autoMode -> "AUTO • MONITORING"
            torchOn -> "LIGHT ON"
            else -> "READY • LIGHT OFF"
        }
        powerButton.text = if (torchOn) "☼" else "⏻"
        glow.alpha = if (torchOn) 1f else .45f
        powerButton.alpha = if (torchOn) 1f else .92f
        autoButton.background = rounded(
            if (autoMode) Color.rgb(22, 75, 126) else Color.rgb(20, 33, 54),
            15, if (autoMode) blue else Color.rgb(42, 61, 88)
        )
        manualButton.background = rounded(
            if (!autoMode) Color.rgb(22, 75, 126) else Color.rgb(20, 33, 54),
            15, if (!autoMode) blue else Color.rgb(42, 61, 88)
        )
        strengthSlider.isEnabled = !autoMode
        strengthSlider.alpha = if (autoMode) .42f else 1f
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean, spacing: Float) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            letterSpacing = spacing
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        }

    private fun modeChip(value: String) = TextView(this).apply {
        text = value
        gravity = Gravity.CENTER
        textSize = 11f
        letterSpacing = .13f
        setTextColor(primaryTextColor)
        background = rounded(Color.rgb(20, 33, 54), 15, Color.rgb(42, 61, 88))
    }

    private fun circle(color: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
        setStroke(dp(1), stroke)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun match() = LinearLayout.LayoutParams(-1, -2)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA) {
            hasCameraPermission = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
            if (!hasCameraPermission) Toast.makeText(this, "Camera permission is needed for flashlight control.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onResume() {
        super.onResume()
        if (autoMode && lightSensor != null) sensorManager.registerListener(this, lightSensor, SensorManager.SENSOR_DELAY_NORMAL)
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
        if (torchOn) {
            try { torchCameraId?.let { cameraManager.setTorchMode(it, false) } } catch (_: Exception) {}
            torchOn = false
            refreshUi()
        }
    }

    override fun onDestroy() {
        sensorManager.unregisterListener(this)
        try { torchCameraId?.let { cameraManager.setTorchMode(it, false) } } catch (_: Exception) {}
        super.onDestroy()
    }

    companion object { private const val REQUEST_CAMERA = 41 }
}
