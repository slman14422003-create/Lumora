package com.lumora.flashlight

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.res.ColorStateList
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.transition.ChangeBounds
import android.transition.TransitionManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import java.util.Date
import kotlin.math.roundToInt

data class UiState(
    val torchOn: Boolean,
    val autoMode: Boolean,
    val strength: Int,
    val strengthSupported: Boolean,
    val lightMode: LightMode = LightMode.STEADY,
    val timerMinutes: Int = 0,
    val timerEndsAt: Long = 0L
)

@SuppressLint("ViewConstructor", "UseSwitchCompatOrMaterialCode")
class MainScreen(
    private val activity: Activity,
    private var amoled: Boolean,
    keepOn: Boolean,
    private var screenFallback: Boolean,
    private val listener: Listener
) {
    interface Listener {
        fun onPowerClick()
        fun onManualClick()
        fun onAutoClick()
        fun onStrengthChanged(strength: Int, fromUser: Boolean)
        fun onKeepOnChanged(checked: Boolean)
        fun onAmoledChanged(checked: Boolean)
        fun onPresetSelected(preset: Preset)
        fun onSensitivitySelected(sensitivity: Sensitivity)
        fun onCalibrate(bright: Boolean)
        fun onCameraToggled(checked: Boolean)
        fun onResetLearning()
        fun onReadingMode()
        fun onLightModeSelected(mode: LightMode)
        fun onTimerSelected(minutes: Int)
        fun onScreenFallbackChanged(checked: Boolean)
        fun onOpenScreenLight()
        fun onLanguageSelected(code: String)
    }

    private fun color(@ColorRes id: Int) = activity.getColor(id)
    private fun string(@StringRes id: Int, vararg args: Any): String =
        if (args.isEmpty()) activity.getString(id) else activity.getString(id, *args)

    private val textColor = color(R.color.lumora_text)
    private val muted = color(R.color.lumora_muted)
    private val accent = color(R.color.lumora_cyan)
    private val blue = color(R.color.lumora_blue)
    private val warm = color(R.color.lumora_warm)
    private val selectedFill = color(R.color.lumora_selected)
    private val white = color(R.color.lumora_white)
    private val transparent = color(R.color.lumora_transparent)

    private val bgColor get() = color(if (amoled) R.color.lumora_black else R.color.lumora_night)
    private val cardColor get() = color(if (amoled) R.color.amoled_card else R.color.lumora_card)
    private val strokeColor get() = color(if (amoled) R.color.amoled_stroke else R.color.lumora_stroke)
    private val powerOffStart
        get() = color(if (amoled) R.color.amoled_power_off_start else R.color.lumora_power_off_start)

    private val regular = ResourcesCompat.getFont(activity, R.font.outfit_regular)
    private val bold = ResourcesCompat.getFont(activity, R.font.outfit_bold)

    private val cards = mutableListOf<View>()
    private var lastState: UiState? = null
    var language = ""
    var preset = Preset.BALANCED
    var sensitivity = Sensitivity.NORMAL
    var cameraEnabled = false
        set(value) {
            field = value
            cameraSwitch?.let { if (it.isChecked != value) it.isChecked = value }
        }
    private var cameraSwitch: Switch? = null

    val root = ScrollView(activity).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val page = column().apply { setPaddingRelative(dp(22), dp(18), dp(22), dp(24)) }
    private val glow = GlowView(activity)
    private val power = ImageView(activity)
    private val status = text(string(R.string.status_light_off), 18f, textColor, true)
    private val strengthValue = text(string(R.string.strength_max), 14f, textColor, true)
    private val slider = SeekBar(activity)
    private val strengthNote = text(string(R.string.strength_unsupported_note), 13f, muted)
    private val indicator = View(activity)
    private lateinit var manualSegment: LinearLayout
    private lateinit var autoSegment: LinearLayout
    private val gauge = LuxGaugeView(activity, bold)
    private val sparkline = LuxSparklineView(activity)
    private val autoPanel = column()
    private val manualPanel = column()
    private val sliderTitle = text(string(R.string.brightness), 15f, muted)
    private val sceneTitle = text(string(Scene.UNKNOWN.title), 17f, textColor, true)
    private val sceneDetail = text("", 13f, accent)
    private val sceneTip = text(string(Scene.UNKNOWN.tip), 13f, muted)
    private val sceneHint = text("", 13f, warm)
    private val reason = text(string(R.string.reason_starting), 15f, textColor, true)
    private val flashWarning = text(string(R.string.flash_warning), 12f, warm)
    private val timerNote = text("", 13f, accent)
    private val sourceNames = listOf(
        R.string.source_light, R.string.source_front_cam, R.string.source_proximity, R.string.source_motion
    )
    private val chips = sourceNames.map { it to text(string(it), 12f, textColor, true) }
    private var repaintLightModes: () -> Unit = {}
    private var repaintTimer: () -> Unit = {}
    private val reasonText = ContextText(activity)

    init {
        root.addView(page)
        if (Build.VERSION.SDK_INT >= 30) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        buildHeader()
        buildPower()
        buildBrightness()
        buildModes()
        buildManualPanel()
        buildAutoPanel()
        buildKeepOn(keepOn)
        val reading = button(string(R.string.reading_mode)) { listener.onReadingMode() }
        page.addView(reading, match().apply { topMargin = dp(12) })
        applyTheme()
    }

    private fun buildHeader() {
        val header = row()
        header.addView(icon(R.drawable.ic_sparkle, accent), size(28, 28))
        header.addView(text(string(R.string.app_name), 20f, textColor, true).apply {
            letterSpacing = .12f
            setPaddingRelative(dp(10), 0, 0, 0)
        }, weighted())
        val settings = icon(R.drawable.ic_settings, textColor).apply {
            setPaddingRelative(dp(12), dp(12), dp(12), dp(12))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = string(R.string.cd_open_settings)
            isFocusable = true
            background = ripple(null, oval(white))
            setOnClickListener { showSettings() }
        }
        header.addView(settings, size(48, 48))
        page.addView(header, match())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildPower() {
        val available = activity.resources.configuration.screenWidthDp - 44
        val orbSize = available.coerceIn(240, 310)
        val buttonSize = (orbSize * .58f).roundToInt()
        val orb = FrameLayout(activity)
        orb.addView(glow, FrameLayout.LayoutParams(-1, -1))
        gauge.visibility = View.INVISIBLE
        orb.addView(gauge, FrameLayout.LayoutParams(-1, -1))
        power.apply {
            setImageResource(R.drawable.ic_power)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val inset = dp((buttonSize * .29f).roundToInt())
            setPaddingRelative(inset, inset, inset, inset)
            isFocusable = true
            elevation = dp(8).toFloat()
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(.95f).scaleY(.95f).setDuration(90).start()
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
                }
                false
            }
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                listener.onPowerClick()
            }
        }
        orb.addView(power, FrameLayout.LayoutParams(dp(buttonSize), dp(buttonSize), Gravity.CENTER))
        page.addView(orb, size(orbSize, orbSize).apply { topMargin = dp(12) })

        status.gravity = Gravity.CENTER
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        page.addView(status, match())

        timerNote.gravity = Gravity.CENTER
        timerNote.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        timerNote.visibility = View.GONE
        page.addView(timerNote, match())
    }

    private fun buildBrightness() {
        val card = card(column())
        val header = row()
        header.addView(icon(R.drawable.ic_sun, accent), size(20, 20))
        sliderTitle.setPaddingRelative(dp(10), 0, 0, 0)
        header.addView(sliderTitle, weighted())
        header.addView(strengthValue)
        card.addView(header, match())
        slider.apply {
            progressDrawable = activity.getDrawable(R.drawable.slider_track)
            thumb = activity.getDrawable(R.drawable.slider_thumb)
            progressTintList = null
            progressBackgroundTintList = null
            thumbTintList = null
            splitTrack = false
            max = 9
            progress = 9
            minimumHeight = dp(48)
            setPaddingRelative(dp(13), dp(10), dp(13), dp(10))
            contentDescription = string(R.string.brightness)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar?, value: Int, fromUser: Boolean) {
                    if (lastState?.autoMode != true) {
                        listener.onStrengthChanged(value + 1, fromUser)
                    } else if (fromUser) {
                        sensitivity = Sensitivity.entries[value.coerceIn(0, 2)]
                        showSliderValue()
                        listener.onSensitivitySelected(sensitivity)
                    }
                }
                override fun onStartTrackingTouch(bar: SeekBar?) = Unit
                override fun onStopTrackingTouch(bar: SeekBar?) = Unit
            })
        }
        card.addView(slider, match().apply { topMargin = dp(6) })
        card.addView(strengthNote, match())
        page.addView(card, match().apply { topMargin = dp(18) })
    }

    private fun buildModes() {
        val box = card(FrameLayout(activity), 4)
        val track = row()
        indicator.background = rounded(selectedFill, 14, blue)
        track.addView(indicator, LinearLayout.LayoutParams(0, -1, 1f))
        track.addView(View(activity), LinearLayout.LayoutParams(0, -1, 1f))
        box.addView(track, FrameLayout.LayoutParams(-1, -1))

        val labels = row()
        manualSegment = segment(
            string(R.string.mode_manual), R.drawable.ic_power, string(R.string.cd_manual_mode)
        ) { listener.onManualClick() }
        autoSegment = segment(
            string(R.string.mode_auto), R.drawable.ic_sparkle, string(R.string.cd_auto_mode)
        ) { listener.onAutoClick() }
        labels.addView(manualSegment, LinearLayout.LayoutParams(0, -1, 1f))
        labels.addView(autoSegment, LinearLayout.LayoutParams(0, -1, 1f))
        box.addView(labels, FrameLayout.LayoutParams(-1, -1))
        indicator.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (!v.isLaidOut || lastState == null) return@addOnLayoutChangeListener
            v.translationX = indicatorOffset(lastState?.autoMode == true)
        }
        page.addView(box, size(-1, 56).apply { topMargin = dp(14) })
    }

    private fun segment(label: String, iconRes: Int, description: String, onClick: () -> Unit) =
        row().apply {
            gravity = Gravity.CENTER
            addView(icon(iconRes, textColor), size(18, 18))
            addView(text(label, 15f, textColor, true).apply { setPaddingRelative(dp(8), 0, 0, 0) })
            contentDescription = description
            isFocusable = true
            background = ripple(null, rounded(white, 14))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onClick()
            }
        }

    // Light pattern and auto-off timer controls. Only shown in manual mode.
    private fun buildManualPanel() {
        val modeCard = card(column(), 14)
        repaintLightModes = addChoices(
            modeCard, string(R.string.light_mode_title), LightMode.entries.toList(),
            { string(it.label) }, { lastState?.lightMode ?: LightMode.STEADY }, 0
        ) { listener.onLightModeSelected(it) }
        flashWarning.visibility = View.GONE
        modeCard.addView(flashWarning, match().apply { topMargin = dp(8) })
        manualPanel.addView(modeCard, match())

        val timerCard = card(column(), 14)
        val minutes = listOf(0, 5, 15, 30, 60)
        repaintTimer = addChoices(
            timerCard, string(R.string.timer_title), minutes,
            { if (it == 0) string(R.string.timer_off) else string(R.string.timer_minutes, it) },
            { lastState?.timerMinutes ?: 0 }, 0
        ) { listener.onTimerSelected(it) }
        manualPanel.addView(timerCard, match().apply { topMargin = dp(10) })
        page.addView(manualPanel, match().apply { topMargin = dp(12) })
    }

    private fun buildAutoPanel() {
        val reasonView = reason
        reasonView.gravity = Gravity.CENTER
        reasonView.maxLines = 1
        reasonView.ellipsize = TextUtils.TruncateAt.END
        autoPanel.addView(reasonView, match())

        val sourceRow = row()
        chips.forEachIndexed { index, (_, chip) ->
            chip.gravity = Gravity.CENTER
            chip.maxLines = 1
            chip.isFocusable = true
            sourceRow.addView(chip, LinearLayout.LayoutParams(0, dp(34), 1f).apply {
                if (index > 0) marginStart = dp(6)
            })
        }
        autoPanel.addView(sourceRow, match().apply { topMargin = dp(10) })

        val history = card(column(), 14)
        val historyTitle = row()
        historyTitle.addView(icon(R.drawable.ic_sensor, accent), size(18, 18))
        historyTitle.addView(
            text(string(R.string.history_title), 14f, textColor, true)
                .apply { setPaddingRelative(dp(8), 0, 0, 0) }
        )
        history.addView(historyTitle, match())
        history.addView(sparkline, size(-1, 72).apply { topMargin = dp(8) })
        history.addView(text(string(R.string.history_legend), 12f, muted), match())
        autoPanel.addView(history, match().apply { topMargin = dp(10) })

        val environment = card(column(), 14)
        environment.addView(text(string(R.string.environment_title), 13f, muted), match())
        listOf(sceneTitle, sceneDetail, sceneTip, sceneHint).forEach { environment.addView(it, match()) }
        sceneHint.visibility = View.GONE
        autoPanel.addView(environment, match().apply { topMargin = dp(10) })

        autoPanel.visibility = View.GONE
        page.addView(autoPanel, match().apply { topMargin = dp(12) })
    }

    private fun buildKeepOn(checked: Boolean) {
        val card = card(row(), 12)
        card.addView(text(string(R.string.keep_on), 15f, muted), weighted())
        card.addView(switch(checked, string(R.string.keep_on)) { listener.onKeepOnChanged(it) })
        page.addView(card, match().apply { topMargin = dp(12) })
    }

    private fun showSettings() {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = column().apply { setPaddingRelative(dp(22), dp(20), dp(22), dp(20)) }
        fun paint() {
            sheet.background = rounded(cardColor, 24, strokeColor)
        }
        paint()
        sheet.addView(text(string(R.string.settings_title), 20f, textColor, true), match())
        val line = row()
        line.addView(text(string(R.string.amoled_theme), 15f, textColor), weighted())
        line.addView(switch(amoled, string(R.string.amoled_theme)) {
            amoled = it
            applyTheme()
            paint()
            listener.onAmoledChanged(it)
        })
        sheet.addView(line, match().apply { topMargin = dp(14) })
        addChoices(
            sheet, string(R.string.auto_preset), Preset.entries.toList(), { presetLabel(it) }, { preset }, 16
        ) {
            preset = it
            listener.onPresetSelected(it)
        }
        addChoices(
            sheet, string(R.string.language_title), listOf("", "en", "ar"),
            {
                when (it) {
                    "en" -> string(R.string.language_english)
                    "ar" -> string(R.string.language_arabic)
                    else -> string(R.string.language_system)
                }
            }, { language }, 16
        ) {
            language = it
            listener.onLanguageSelected(it)
        }
        sheet.addView(
            text(string(R.string.calibration_title), 15f, muted),
            match().apply { topMargin = dp(16) }
        )
        val calibration = row()
        calibration.addView(button(string(R.string.calibrate_dark)) { listener.onCalibrate(false) }, weighted())
        calibration.addView(button(string(R.string.calibrate_bright)) { listener.onCalibrate(true) }, weighted())
        sheet.addView(calibration, match())
        val cameraLine = row()
        cameraLine.addView(text(string(R.string.camera_sensing), 15f, textColor), weighted())
        val toggle = switch(cameraEnabled, string(R.string.cd_camera_sensing)) {
            if (it != cameraEnabled) listener.onCameraToggled(it)
        }
        cameraSwitch = toggle
        cameraLine.addView(toggle)
        sheet.addView(cameraLine, match().apply { topMargin = dp(14) })
        sheet.addView(text(string(R.string.camera_privacy), 13f, muted), match())
        val fallbackLine = row()
        fallbackLine.addView(text(string(R.string.screen_fallback), 15f, textColor), weighted())
        fallbackLine.addView(switch(screenFallback, string(R.string.screen_fallback)) {
            screenFallback = it
            listener.onScreenFallbackChanged(it)
        })
        sheet.addView(fallbackLine, match().apply { topMargin = dp(14) })
        sheet.addView(button(string(R.string.open_screen_light)) {
            dialog.dismiss()
            listener.onOpenScreenLight()
        }, match())
        sheet.addView(button(string(R.string.reset_learning)) { listener.onResetLearning() }, match())
        dialog.setOnDismissListener { cameraSwitch = null }
        dialog.setContentView(ScrollView(activity).apply { addView(sheet) })
        dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(transparent))
            setGravity(Gravity.BOTTOM)
            setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun presetLabel(preset: Preset) = string(
        when (preset) {
            Preset.ECO -> R.string.preset_eco
            Preset.BALANCED -> R.string.preset_balanced
            Preset.MAX -> R.string.preset_max
        }
    )

    private fun sensitivityLabel(sensitivity: Sensitivity) = string(
        when (sensitivity) {
            Sensitivity.LOW -> R.string.sensitivity_low
            Sensitivity.NORMAL -> R.string.sensitivity_normal
            Sensitivity.HIGH -> R.string.sensitivity_high
        }
    )

    // Adds a titled row of exclusive choices and returns a function that repaints the selection.
    private fun <T> addChoices(
        parent: LinearLayout,
        title: String,
        values: List<T>,
        label: (T) -> String,
        current: () -> T,
        topMargin: Int,
        onPick: (T) -> Unit
    ): () -> Unit {
        parent.addView(text(title, 15f, muted), match().apply { this.topMargin = dp(topMargin) })
        val line = row()
        val buttons = mutableListOf<Button>()
        fun paint() {
            buttons.forEachIndexed { index, button ->
                val selected = values[index] == current()
                button.isSelected = selected
                ViewCompat.setStateDescription(
                    button,
                    string(if (selected) R.string.state_selected else R.string.state_not_selected)
                )
                button.background = ripple(
                    rounded(
                        if (selected) selectedFill else transparent,
                        12,
                        if (selected) blue else strokeColor
                    ),
                    rounded(white, 12)
                )
            }
        }
        values.forEach { value ->
            val name = label(value)
            val choice = button(name, compact = values.size > 3) {
                onPick(value)
                paint()
            }
            choice.contentDescription = string(R.string.value_description, title, name)
            buttons.add(choice)
            line.addView(choice, weighted())
        }
        paint()
        parent.addView(line, match())
        return ::paint
    }

    private fun button(label: String, compact: Boolean = false, onClick: () -> Unit) =
        Button(activity).apply {
            text = label
            isAllCaps = false
            textSize = if (compact) 13f else 14f
            typeface = bold
            setTextColor(textColor)
            stateListAnimator = null
            minimumHeight = dp(48)
            if (compact) {
                minWidth = 0
                minimumWidth = 0
                maxLines = 1
                setPaddingRelative(dp(2), 0, dp(2), 0)
            }
            background = ripple(rounded(transparent, 12, strokeColor), rounded(white, 12))
            setOnClickListener { onClick() }
        }

    private fun applyTheme() {
        root.setBackgroundColor(bgColor)
        activity.window.setBackgroundDrawable(ColorDrawable(bgColor))
        if (Build.VERSION.SDK_INT < 30) {
            @Suppress("DEPRECATION")
            activity.window.statusBarColor = bgColor
            @Suppress("DEPRECATION")
            activity.window.navigationBarColor = bgColor
        }
        cards.forEach { it.background = rounded(cardColor, 18, strokeColor) }
        lastState?.let { paintPower(it.torchOn) }
    }

    private fun paintPower(on: Boolean) {
        val fill = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            if (on) intArrayOf(color(R.color.lumora_power_on_start), warm)
            else intArrayOf(powerOffStart, cardColor)
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(dp(2), if (on) white else blue)
        }
        power.background = ripple(fill, oval(white))
        power.imageTintList = ColorStateList.valueOf(if (on) color(R.color.lumora_on_warm) else textColor)
    }

    fun render(state: UiState) {
        val previous = lastState
        if (previous == state) return
        lastState = state
        val modeChanged = previous != null && previous.autoMode != state.autoMode

        if (previous?.torchOn != state.torchOn) paintPower(state.torchOn)
        glow.setOn(state.torchOn)
        power.contentDescription =
            string(if (state.torchOn) R.string.cd_power_off else R.string.cd_power_on)

        val message = string(
            when {
                state.autoMode && state.torchOn -> R.string.status_auto_on
                state.autoMode -> R.string.status_auto_watching
                state.torchOn -> R.string.status_light_on
                else -> R.string.status_light_off
            }
        )
        if (status.text.toString() != message) {
            status.text = message
            status.alpha = .2f
            status.animate().alpha(1f).setDuration(250).start()
        }
        status.contentDescription = string(R.string.status_description, message)
        status.setTextColor(if (state.torchOn) warm else textColor)

        val timerVisible = state.torchOn && !state.autoMode && state.timerEndsAt > 0L
        if (timerVisible) {
            val time = DateFormat.getTimeFormat(activity).format(Date(state.timerEndsAt))
            timerNote.text = string(R.string.timer_note, time)
        }
        timerNote.visibility = if (timerVisible) View.VISIBLE else View.GONE

        if (modeChanged) {
            TransitionManager.beginDelayedTransition(page, ChangeBounds().setDuration(280))
        }
        if (previous == null || modeChanged) {
            if (state.autoMode) resetAutoViews()
            reveal(autoPanel, state.autoMode, modeChanged, View.GONE)
            reveal(gauge, state.autoMode, modeChanged, View.INVISIBLE)
            reveal(manualPanel, !state.autoMode, modeChanged, View.GONE)
        }
        flashWarning.visibility =
            if (state.lightMode == LightMode.STEADY) View.GONE else View.VISIBLE
        repaintLightModes()
        repaintTimer()

        manualSegment.isSelected = !state.autoMode
        autoSegment.isSelected = state.autoMode
        ViewCompat.setStateDescription(
            manualSegment,
            string(if (state.autoMode) R.string.state_not_selected else R.string.state_selected)
        )
        ViewCompat.setStateDescription(
            autoSegment,
            string(if (state.autoMode) R.string.state_selected else R.string.state_not_selected)
        )
        if (indicator.isLaidOut) {
            val target = indicatorOffset(state.autoMode)
            if (modeChanged) indicator.animate().translationX(target).setDuration(280).start()
            else if (previous == null) indicator.translationX = target
        }

        showSlider(state, modeChanged)
    }

    private fun showSlider(state: UiState, animate: Boolean) {
        val auto = state.autoMode
        val max = if (auto) 2 else 9
        val progress = if (auto) sensitivity.ordinal else state.strength - 1
        if (slider.max != max) slider.max = max
        if (slider.progress != progress) slider.progress = progress
        slider.isEnabled = auto || state.strengthSupported
        val title = string(if (auto) R.string.sensitivity else R.string.brightness)
        slider.contentDescription = title
        sliderTitle.text = title
        showSliderValue()
        strengthNote.visibility = if (auto || state.strengthSupported) View.GONE else View.VISIBLE
        val alpha = if (slider.isEnabled) 1f else .35f
        if (animate) {
            listOf(sliderTitle, strengthValue, slider).forEach {
                it.animate().cancel()
                it.alpha = 0f
                it.animate().alpha(if (it === slider) alpha else 1f).setDuration(280).start()
            }
        } else {
            slider.alpha = alpha
        }
    }

    private fun showSliderValue() {
        val state = lastState ?: return
        strengthValue.text = when {
            state.autoMode -> sensitivityLabel(sensitivity)
            !state.strengthSupported -> string(R.string.strength_on_off)
            state.strength == 10 -> string(R.string.strength_max)
            else -> string(R.string.strength_fraction, state.strength)
        }
        val title = string(if (state.autoMode) R.string.sensitivity else R.string.brightness)
        strengthValue.contentDescription = string(R.string.value_description, title, strengthValue.text)
    }

    private fun reveal(view: View, show: Boolean, animate: Boolean, hidden: Int) {
        view.animate().cancel()
        if (!animate) {
            view.visibility = if (show) View.VISIBLE else hidden
            view.alpha = 1f
            view.scaleX = 1f
            view.scaleY = 1f
            return
        }
        if (show) {
            view.visibility = View.VISIBLE
            view.alpha = 0f
            view.scaleX = .9f
            view.scaleY = .9f
            view.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(320).start()
        } else {
            view.animate().alpha(0f).scaleX(.9f).scaleY(.9f).setDuration(200)
                .withEndAction { view.visibility = hidden }.start()
        }
    }

    private fun resetAutoViews() {
        gauge.setLux(null)
        showEnvironment(Environment())
        sparkline.clear()
        reason.text = string(R.string.reason_starting)
        chips.forEach { (name, chip) -> paintChip(chip, name, ChipState.INACTIVE) }
    }

    private enum class ChipState(@StringRes val label: Int) {
        ACTIVE(R.string.chip_state_active),
        INACTIVE(R.string.chip_state_inactive),
        UNAVAILABLE(R.string.chip_state_unavailable)
    }

    private fun paintChip(chip: TextView, @StringRes name: Int, state: ChipState) {
        val active = state == ChipState.ACTIVE
        chip.background = rounded(
            if (active) selectedFill else transparent,
            17,
            if (active) accent else strokeColor
        )
        chip.setTextColor(if (active) textColor else muted)
        chip.alpha = if (state == ChipState.UNAVAILABLE) .4f else 1f
        chip.contentDescription = string(R.string.chip_description, string(name), string(state.label))
    }

    fun showScreenLight(whiteLight: Boolean, autoCloseMinutes: Int = 0) {
        val dialog = Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val background = color(if (whiteLight) R.color.screen_light_white else R.color.screen_light_warm)
        val textTone = color(if (whiteLight) R.color.screen_light_white_text else R.color.screen_light_warm_text)
        val message = string(if (whiteLight) R.string.screen_light_white else R.string.screen_light_reading)
        val surface = text(message, 14f, textTone).apply {
            gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
            setPaddingRelative(dp(24), dp(24), dp(24), dp(48))
            setBackgroundColor(background)
            contentDescription = message
            setOnClickListener { dialog.dismiss() }
        }
        dialog.setContentView(surface)
        dialog.window?.let {
            it.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            it.attributes = it.attributes.apply { screenBrightness = if (whiteLight) 1f else .7f }
        }
        dialog.show()
        if (autoCloseMinutes > 0) {
            surface.postDelayed({
                if (dialog.isShowing) dialog.dismiss()
            }, autoCloseMinutes * 60_000L)
        }
    }

    private fun showEnvironment(environment: Environment) {
        sceneTitle.text = string(environment.scene.title)
        sceneDetail.text = string(
            R.string.environment_detail,
            string(environment.warmth.title),
            string(environment.flicker.title)
        ).replaceFirstChar { it.uppercase() }
        sceneTip.text = string(environment.scene.tip)
        val hint = environment.hint
        sceneHint.text = if (hint == null) "" else string(hint)
        sceneHint.visibility = if (hint == null) View.GONE else View.VISIBLE
    }

    fun showAuto(lux: Float?, strength: Int, why: Reason, sources: Sources, environment: Environment) {
        showEnvironment(environment)
        gauge.setLux(lux)
        if (lux != null) sparkline.add(SystemClock.elapsedRealtime(), lux, strength)
        val line = reasonLine(reasonText, why, strength, lastState?.strengthSupported == true)
        if (reason.text.toString() != line) reason.text = line
        fun state(available: Boolean, active: Boolean = true) = when {
            !available -> ChipState.UNAVAILABLE
            active -> ChipState.ACTIVE
            else -> ChipState.INACTIVE
        }
        val states = listOf(
            state(sources.light, why != Reason.PAUSED),
            state(cameraEnabled, environment.cameraActive),
            state(sources.proximity),
            state(sources.motion)
        )
        chips.forEachIndexed { index, (name, chip) -> paintChip(chip, name, states[index]) }
    }

    private fun indicatorOffset(auto: Boolean): Float {
        if (!auto) return 0f
        val direction = if (indicator.layoutDirection == View.LAYOUT_DIRECTION_RTL) -1 else 1
        return (indicator.width * direction).toFloat()
    }

    private fun switch(checked: Boolean, description: String, onChange: (Boolean) -> Unit) =
        Switch(activity).apply {
            isChecked = checked
            minimumHeight = dp(48)
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(accent, muted))
            trackTintList = ColorStateList(
                states, intArrayOf(blue, color(R.color.lumora_switch_track_off))
            )
            contentDescription = description
            setOnCheckedChangeListener { _, value -> onChange(value) }
        }

    private fun <T : ViewGroup> card(view: T, padding: Int = 16): T {
        view.setPaddingRelative(dp(padding), dp(padding), dp(padding), dp(padding))
        cards.add(view)
        return view
    }

    private fun text(value: String, size: Float, color: Int, strong: Boolean = false) =
        TextView(activity).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (strong) bold else regular
            letterSpacing = .01f
        }

    private fun icon(res: Int, color: Int) = ImageView(activity).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(color)
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun ripple(content: Drawable?, mask: Drawable) =
        RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(accent, 70)), content, mask)

    private fun oval(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }

    private fun row() = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL }

    private fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
    }

    private fun match() = LinearLayout.LayoutParams(-1, -2)

    private fun weighted() = LinearLayout.LayoutParams(0, -2, 1f)

    private fun size(width: Int, height: Int) = LinearLayout.LayoutParams(
        if (width < 0) width else dp(width), if (height < 0) height else dp(height)
    )

    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
}
