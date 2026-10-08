package com.keyfekederradyo.android

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Music-reactive neon spectrum. Reads the live FFT from [SpectrumAnalyzer]; while buffering
 * (no audio yet) it breathes gently. [full] = mirrored bars with reflection for the big player.
 */
class AudioSpectrumView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val full: Boolean = false,
) : View(context, attrs) {
    private val raw = FloatArray(SpectrumAnalyzer.BANDS)
    private val level = FloatArray(SpectrumAnalyzer.BANDS)
    private val peaks = FloatArray(SpectrumAnalyzer.BANDS)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val peakPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var active = false
    private var phase = 0f
    private var seed = 0
    private var colorTop = Neon.PINK
    private var colorBottom = Neon.ORANGE
    private var shaderHeight = -1f

    fun setPlaying(playing: Boolean) {
        active = playing
        postInvalidateOnAnimation()
    }

    fun setStationSeed(value: Int) { seed = value }
    fun restart() { phase = 0f; postInvalidateOnAnimation() }

    fun setAccent(color: Int) {
        colorBottom = color
        colorTop = Neon.mix(color, Neon.PINK, .65f)
        shaderHeight = -1f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val bands = SpectrumAnalyzer.BANDS
        val count = if (full) bands else min(bands, max(16, (w / Neon.dp(context, 7f)).toInt()))
        val hasAudio = active && SpectrumAnalyzer.read(raw)
        phase += .06f

        // Map analyser bands onto the visible bar count, smooth (fast attack, slow decay), track peaks
        var moving = false
        for (i in 0 until count) {
            val src = i * bands / count
            val target = when {
                hasAudio -> raw[src]
                active -> .10f + .08f * abs(sin(phase * 1.3f + i * .45f + seed % 7))
                else -> 0.03f
            }
            val k = if (target > level[i]) .55f else .14f
            level[i] += (target - level[i]) * k
            peaks[i] = if (level[i] > peaks[i]) level[i] else max(0f, peaks[i] - .012f)
            if (abs(target - level[i]) > .002f || peaks[i] > .01f) moving = true
        }

        val base = if (full) h * .62f else h
        val maxH = if (full) h * .6f else h
        if (shaderHeight != base) {
            barPaint.shader = LinearGradient(0f, base - maxH, 0f, base, colorTop, colorBottom, Shader.TileMode.CLAMP)
            shaderHeight = base
        }
        val slot = w / count
        val barW = slot * (if (full) .58f else .55f)
        val radius = barW / 2

        for (i in 0 until count) {
            val x = i * slot + (slot - barW) / 2
            val bh = max(barW, level[i] * maxH)
            // two soft halo passes = cheap neon bloom
            glowPaint.color = Neon.withAlpha(Neon.mix(colorBottom, colorTop, level[i]), (40 * (.3f + level[i])).toInt())
            rect.set(x - barW * .9f, base - bh - barW * .9f, x + barW * 1.9f, base + barW * .3f)
            canvas.drawRoundRect(rect, radius * 2, radius * 2, glowPaint)
            glowPaint.alpha = (60 * (.3f + level[i])).toInt()
            rect.set(x - barW * .4f, base - bh - barW * .4f, x + barW * 1.4f, base)
            canvas.drawRoundRect(rect, radius * 1.5f, radius * 1.5f, glowPaint)

            barPaint.alpha = 255
            rect.set(x, base - bh, x + barW, base)
            canvas.drawRoundRect(rect, radius, radius, barPaint)

            if (full) {
                // reflection
                barPaint.alpha = 60
                rect.set(x, base + Neon.dp(context, 3f), x + barW, base + Neon.dp(context, 3f) + bh * .38f)
                canvas.drawRoundRect(rect, radius, radius, barPaint)
                // falling peak cap
                peakPaint.color = 0xE6FFFFFF.toInt()
                val py = base - max(barW, peaks[i] * maxH) - Neon.dp(context, 4f)
                rect.set(x, py - Neon.dp(context, 2.5f), x + barW, py)
                canvas.drawRoundRect(rect, radius, radius, peakPaint)
            }
        }
        if (active || moving) postInvalidateOnAnimation()
    }
}
