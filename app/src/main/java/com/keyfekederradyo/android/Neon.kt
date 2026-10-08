package com.keyfekederradyo.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/** "Neon gece" design tokens and small drawing helpers shared by every screen. */
object Neon {
    const val BG = 0xFF07070B.toInt()
    const val BG2 = 0xFF0E0D14.toInt()
    const val GLASS = 0x1AFFFFFF
    const val GLASS_STRONG = 0x26FFFFFF
    const val STROKE = 0x1FFFFFFF
    const val TEXT = 0xFFF6F3FF.toInt()
    const val MUTED = 0xFF9C98AD.toInt()
    const val ORANGE = 0xFFFF7A1A.toInt()
    const val PINK = 0xFFFF2E88.toInt()
    const val VIOLET = 0xFF8B5CFF.toInt()
    const val CYAN = 0xFF2EE6D6.toInt()

    /** The one brand gradient used by every button, glow and highlight. */
    val BRAND = intArrayOf(ORANGE, PINK)

    fun dp(context: Context, v: Float) = v * context.resources.displayMetrics.density
    fun dp(context: Context, v: Int) = (v * context.resources.displayMetrics.density).toInt()

    fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    fun mix(a: Int, b: Int, t: Float): Int = Color.argb(
        (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t).toInt(),
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )
}

/**
 * Glass card: translucent fill, hairline border, optional neon glow halo around it.
 * The halo is drawn outside the card bounds, so give the view some margin.
 */
class GlassDrawable(
    private val context: Context,
    var radiusDp: Float = 22f,
    var fill: Int = Neon.GLASS,
    var stroke: Int = Neon.STROKE,
    var glow: Int = Color.TRANSPARENT,
    var gradient: IntArray? = null,
) : Drawable() {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    override fun draw(canvas: Canvas) {
        rect.set(bounds)
        if (Color.alpha(glow) > 0) {
            // keep the halo inside our bounds so it is never clipped into a hard square
            val halo = minOf(Neon.dp(context, 9f), bounds.width() / 6f, bounds.height() / 6f)
            rect.inset(halo, halo)
            glowPaint.color = glow
            glowPaint.maskFilter = BlurMaskFilter(halo, BlurMaskFilter.Blur.OUTER)
            val gr = minOf(Neon.dp(context, radiusDp), rect.height() / 2, rect.width() / 2)
            canvas.drawRoundRect(rect, gr, gr, glowPaint)
        }
        val r = minOf(Neon.dp(context, radiusDp), rect.height() / 2, rect.width() / 2)
        val g = gradient
        if (g != null) {
            fillPaint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, g, null, Shader.TileMode.CLAMP)
        } else {
            fillPaint.shader = null; fillPaint.color = fill
        }
        canvas.drawRoundRect(rect, r, r, fillPaint)
        // subtle top sheen
        fillPaint.shader = LinearGradient(0f, rect.top, 0f, rect.top + rect.height() * .5f, 0x14FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, r, r, fillPaint)
        strokePaint.strokeWidth = Neon.dp(context, 1f)
        strokePaint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(Neon.withAlpha(stroke, Color.alpha(stroke) * 2), stroke, Neon.withAlpha(stroke, Color.alpha(stroke) / 3)), null, Shader.TileMode.CLAMP)
        rect.inset(strokePaint.strokeWidth / 2, strokePaint.strokeWidth / 2)
        canvas.drawRoundRect(rect, r, r, strokePaint)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * Slowly drifting neon lights behind the whole UI, always in the brand colours.
 * With music playing the lights breathe with the bass; [motion] = false keeps them still.
 */
class AmbientBackgroundView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var t = 0f
    private var energy = 0f
    private val levels = FloatArray(SpectrumAnalyzer.BANDS)
    var motion = true
        set(value) { field = value; if (value && isAttachedToWindow) animator.start() else animator.cancel(); invalidate() }
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
        addUpdateListener { tick() }
    }

    init { setLayerType(LAYER_TYPE_HARDWARE, null) }

    private fun tick() {
        t += 0.0045f
        val e = if (SpectrumAnalyzer.read(levels)) (levels[0] + levels[1] + levels[2] + levels[3]) / 4f else 0f
        energy += (e - energy) * 0.12f
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (motion) animator.start() }
    override fun onDetachedFromWindow() { animator.cancel(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawColor(Neon.BG)
        blob(canvas, w * (.18f + .08f * sin(t * 2.1f)), h * (.10f + .05f * cos(t * 1.7f)), w * (.85f + .25f * energy), Neon.ORANGE, 62 + (55 * energy).toInt())
        blob(canvas, w * (.92f + .06f * cos(t * 1.3f)), h * (.42f + .08f * sin(t * 1.1f)), w * .75f, Neon.PINK, 34 + (36 * energy).toInt())
        blob(canvas, w * (.25f + .1f * sin(t * .9f)), h * (.88f + .04f * cos(t * 1.9f)), w * .8f, Neon.VIOLET, 30)
    }

    private fun blob(canvas: Canvas, x: Float, y: Float, r: Float, color: Int, alpha: Int) {
        paint.shader = RadialGradient(x, y, r, Neon.withAlpha(color, alpha), Neon.withAlpha(color, 0), Shader.TileMode.CLAMP)
        canvas.drawCircle(x, y, r, paint)
    }
}
