package com.keyfekederradyo.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Heart toggle with a "like" burst: the heart pops, a glowing ring expands and
 * small sparks fly out. Unliking just deflates the heart.
 */
class FavoriteButton(context: Context, private val iconDp: Float = 22f) : View(context) {
    private val outline: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_heart_outline)!!.mutate()
    private val filled: Drawable = ContextCompat.getDrawable(context, R.drawable.ic_heart)!!.mutate()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val spark = Paint(Paint.ANTI_ALIAS_FLAG)
    private var progress = 1f   // 0..1 burst animation
    private var scale = 1f
    var isOn = false
        private set
    var idleColor = Neon.TEXT

    init {
        isClickable = true; isFocusable = true
        contentDescription = "Favorilere ekle"
    }

    /** Sets the state without animation (binding). */
    fun setOn(on: Boolean) {
        isOn = on; progress = 1f; scale = 1f
        contentDescription = if (on) "Favorilerden çıkar" else "Favorilere ekle"
        invalidate()
    }

    /** Animates to the new state (user tap). */
    fun animateTo(on: Boolean) {
        setOn(on)
        if (on) {
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 620; interpolator = DecelerateInterpolator()
                addUpdateListener {
                    progress = it.animatedValue as Float
                    // pop: shrink fast, overshoot, settle
                    scale = when {
                        progress < .15f -> 1f - progress * 2.4f
                        progress < .45f -> .64f + (progress - .15f) / .3f * .62f
                        else -> 1.26f - (progress - .45f) / .55f * .26f
                    }
                    invalidate()
                }
                start()
            }
        } else {
            ValueAnimator.ofFloat(.8f, 1f).apply {
                duration = 220
                addUpdateListener { scale = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        val icon = Neon.dp(context, iconDp)
        val reach = min(width, height) / 2f

        if (isOn && progress < 1f) {
            // expanding ring
            val rp = (progress / .55f).coerceAtMost(1f)
            ring.strokeWidth = Neon.dp(context, 3f) * (1f - rp) + 1f
            ring.color = Neon.withAlpha(Neon.PINK, (255 * (1f - rp)).toInt())
            canvas.drawCircle(cx, cy, icon * .4f + reach * .55f * rp, ring)
            // sparks
            val sp = ((progress - .2f) / .8f).coerceIn(0f, 1f)
            if (sp > 0f) {
                for (i in 0 until 8) {
                    val a = i * PI / 4 + PI / 8
                    val dist = icon * .55f + reach * .5f * sp
                    spark.color = Neon.withAlpha(if (i % 2 == 0) Neon.PINK else Neon.ORANGE, (255 * (1f - sp)).toInt())
                    canvas.drawCircle(cx + (cos(a) * dist).toFloat(), cy + (sin(a) * dist).toFloat(), Neon.dp(context, 2.6f) * (1f - sp * .6f), spark)
                }
            }
        }

        val d = if (isOn) filled else outline
        d.setTint(if (isOn) Neon.PINK else idleColor)
        val half = icon * scale / 2
        d.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        d.draw(canvas)
    }
}
