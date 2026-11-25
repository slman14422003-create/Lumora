package com.lumora.flashlight

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Build
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils

class GlowView(context: Context) : View(context) {
    private val idlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val onPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var on = false
    private var intensity = 0f
    private var pulse = 1f

    private val fade = ValueAnimator().apply {
        duration = 420
        addUpdateListener {
            intensity = it.animatedValue as Float
            invalidate()
        }
    }

    private val breathing = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2200
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setOn(value: Boolean) {
        if (on == value) return
        on = value
        fade.cancel()
        fade.setFloatValues(intensity, if (value) 1f else 0f)
        fade.start()
        updateBreathing()
    }

    private fun updateBreathing() {
        val enabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
        if (on && enabled && isAttachedToWindow && windowVisibility == VISIBLE) {
            if (!breathing.isStarted) breathing.start()
        } else {
            breathing.cancel()
            pulse = 1f
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateBreathing()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateBreathing()
    }

    override fun onDetachedFromWindow() {
        breathing.cancel()
        fade.cancel()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w == 0 || h == 0) return
        idlePaint.shader = gradient(w, h, ContextCompat.getColor(context, R.color.lumora_cyan), 90)
        onPaint.shader = gradient(w, h, ContextCompat.getColor(context, R.color.lumora_warm), 215)
    }

    private fun gradient(w: Int, h: Int, color: Int, alpha: Int) = RadialGradient(
        w / 2f, h / 2f, minOf(w, h) / 2f,
        intArrayOf(
            ColorUtils.setAlphaComponent(color, alpha),
            ColorUtils.setAlphaComponent(color, alpha),
            ColorUtils.setAlphaComponent(color, alpha / 4),
            ColorUtils.setAlphaComponent(color, 0)
        ),
        floatArrayOf(0f, .6f, .8f, 1f),
        Shader.TileMode.CLAMP
    )

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f
        idlePaint.alpha = ((1f - intensity) * 255).toInt()
        canvas.drawCircle(cx, cy, radius, idlePaint)
        onPaint.alpha = (intensity * (.6f + .4f * pulse) * 255).toInt()
        val scale = .92f + .08f * pulse
        canvas.save()
        canvas.scale(scale, scale, cx, cy)
        canvas.drawCircle(cx, cy, radius, onPaint)
        canvas.restore()
    }
}
