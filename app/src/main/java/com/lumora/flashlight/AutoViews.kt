package com.lumora.flashlight

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.Typeface
import android.util.TypedValue
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import android.view.View
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin

private fun luxPosition(lux: Float) = (log10(lux.coerceAtLeast(0f) + 1f) / 4f).coerceIn(0f, 1f)

private fun luxColor(position: Float, from: Int, to: Int): Int =
    ColorUtils.blendARGB(from, to, position.coerceIn(0f, 1f))

class LuxGaugeView(context: Context, font: Typeface?) : View(context) {
    private val density = resources.displayMetrics.density
    private val blue = ContextCompat.getColor(context, R.color.lumora_blue)
    private val amber = ContextCompat.getColor(context, R.color.lumora_warm)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 7 * density
        strokeCap = Paint.Cap.ROUND
    }
    private val marker = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = font
        textAlign = Paint.Align.CENTER
        color = ContextCompat.getColor(context, R.color.lumora_text)
        textSize = TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics
        )
    }
    private val bounds = RectF()
    private var position = 0f
    private var lux: Float? = null

    private val mover = ValueAnimator().apply {
        duration = 450
        addUpdateListener {
            position = it.animatedValue as Float
            invalidate()
        }
    }

    init {
        contentDescription = context.getString(R.string.cd_lux_none)
    }

    fun setLux(value: Float?) {
        val changed = value?.roundToInt() != lux?.roundToInt()
        lux = value
        if (!changed) return
        contentDescription =
            if (value == null) context.getString(R.string.cd_lux_none)
            else context.getString(R.string.cd_lux_value, value.roundToInt())
        mover.cancel()
        mover.setFloatValues(position, luxPosition(value ?: 0f))
        mover.start()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val radius = minOf(w, h) * .42f
        bounds.set(w / 2f - radius, h / 2f - radius, w / 2f + radius, h / 2f + radius)
        val gradient = SweepGradient(
            w / 2f, h / 2f,
            intArrayOf(blue, amber, amber, blue, blue),
            floatArrayOf(0f, .75f, .87f, .88f, 1f)
        )
        gradient.setLocalMatrix(Matrix().apply { setRotate(START, w / 2f, h / 2f) })
        arc.shader = gradient
    }

    override fun onDraw(canvas: Canvas) {
        arc.alpha = 60
        canvas.drawArc(bounds, START, SWEEP, false, arc)
        if (lux != null) {
            arc.alpha = 255
            canvas.drawArc(bounds, START, SWEEP * position, false, arc)
            val angle = Math.toRadians((START + SWEEP * position).toDouble())
            val radius = bounds.width() / 2f
            val x = bounds.centerX() + radius * cos(angle).toFloat()
            val y = bounds.centerY() + radius * sin(angle).toFloat()
            marker.color = ContextCompat.getColor(context, R.color.lumora_white)
            canvas.drawCircle(x, y, 9 * density, marker)
            marker.color = luxColor(position, blue, amber)
            canvas.drawCircle(x, y, 6 * density, marker)
        }
        val text = lux?.let { context.getString(R.string.lux_value, it.roundToInt()) }
            ?: context.getString(R.string.lux_none)
        canvas.drawText(text, width / 2f, height * .94f, label)
    }

    override fun onDetachedFromWindow() {
        mover.cancel()
        super.onDetachedFromWindow()
    }

    private companion object {
        const val START = 135f
        const val SWEEP = 270f
    }
}

class LuxSparklineView(context: Context) : View(context) {
    private class Sample(val time: Long, val lux: Float, val strength: Int)

    private val density = resources.displayMetrics.density
    private val blue = ContextCompat.getColor(context, R.color.lumora_blue)
    private val amber = ContextCompat.getColor(context, R.color.lumora_warm)
    private val samples = ArrayDeque<Sample>()
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint().apply { color = ColorUtils.setAlphaComponent(amber, 55) }
    private val grid = Paint().apply {
        color = ColorUtils.setAlphaComponent(ContextCompat.getColor(context, R.color.lumora_muted), 45)
        strokeWidth = density
    }
    private val path = Path()

    init {
        contentDescription = context.getString(R.string.cd_spark_none)
    }

    fun add(time: Long, lux: Float, strength: Int) {
        samples.addLast(Sample(time, lux, strength))
        while (samples.size > 1 && samples[1].time < time - WINDOW_MS) samples.removeFirst()
        val flashlight = if (strength == 0) context.getString(R.string.spark_off)
        else context.getString(R.string.spark_percent, strength * 10)
        contentDescription = context.getString(R.string.cd_spark_value, lux.roundToInt(), flashlight)
        invalidate()
    }

    fun clear() {
        samples.clear()
        contentDescription = context.getString(R.string.cd_spark_none)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val inset = 3 * density
        val usable = height - inset * 2
        for (step in 0..2) {
            val y = inset + usable * step / 2f
            canvas.drawLine(0f, y, width.toFloat(), y, grid)
        }
        val newest = samples.lastOrNull() ?: return
        val start = newest.time - WINDOW_MS
        fun x(time: Long) = (time - start).toFloat() / WINDOW_MS * width
        fun y(position: Float) = inset + usable * (1f - position)

        for (index in 0 until samples.size - 1) {
            val sample = samples[index]
            if (sample.strength == 0) continue
            canvas.drawRect(
                x(sample.time), y(sample.strength / 10f),
                x(samples[index + 1].time), y(0f), fill
            )
        }
        for (index in 1 until samples.size) {
            val from = samples[index - 1]
            val to = samples[index]
            val position = luxPosition(to.lux)
            line.color = luxColor(position, blue, amber)
            path.rewind()
            path.moveTo(x(from.time), y(luxPosition(from.lux)))
            path.lineTo(x(to.time), y(position))
            canvas.drawPath(path, line)
        }
    }

    private companion object {
        const val WINDOW_MS = 30_000L
    }
}
