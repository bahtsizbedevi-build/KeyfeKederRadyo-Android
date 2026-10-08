package com.keyfekederradyo.android

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/** Small view builders so every screen uses the same neon typography, glass cards and chips. */
class Ui(val context: Context) {
    fun dp(v: Int) = Neon.dp(context, v)
    fun dpf(v: Float) = Neon.dp(context, v)

    fun text(value: CharSequence, size: Float, color: Int = Neon.TEXT, bold: Boolean = false, lines: Int = 1) =
        TextView(context).apply {
            text = value; textSize = size; setTextColor(color)
            typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
            if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
            includeFontPadding = false
        }

    fun label(value: String, color: Int = Neon.ORANGE) = text(value.uppercase(), 10.5f, color, true).apply { letterSpacing = .14f }

    fun icon(res: Int, tint: Int = Neon.TEXT, sizeDp: Int = 24) = ImageView(context).apply {
        setImageResource(res); setColorFilter(tint); scaleType = ImageView.ScaleType.CENTER_INSIDE
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Round touch target with an icon; pops on press. */
    fun iconButton(res: Int, desc: String, sizeDp: Int = 46, iconDp: Int = 22, tint: Int = Neon.TEXT, onClick: () -> Unit) =
        FrameLayout(context).apply {
            contentDescription = desc
            isClickable = true; isFocusable = true
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Neon.GLASS) }
            addView(icon(res, tint, iconDp), FrameLayout.LayoutParams(dp(iconDp), dp(iconDp), Gravity.CENTER))
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            setOnClickListener { pop(this); onClick() }
        }

    fun glass(radius: Float = 22f, glow: Int = Color.TRANSPARENT, fill: Int = Neon.GLASS, gradient: IntArray? = null) =
        GlassDrawable(context, radius, fill, Neon.STROKE, glow, gradient)

    fun chip(value: String, iconRes: Int? = null, selected: Boolean = false, onClick: () -> Unit): View {
        val chip = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setPadding(dp(14), 0, dp(16), 0)
            background = if (selected) glass(20f, Neon.withAlpha(Neon.ORANGE, 90), gradient = intArrayOf(0x55FF7A1A, 0x40FF2E88))
            else glass(20f)
            isClickable = true; isFocusable = true
            setOnClickListener { pop(this); onClick() }
        }
        if (iconRes != null) chip.addView(icon(iconRes, if (selected) Neon.TEXT else Neon.ORANGE, 18).apply {
            (layoutParams as LinearLayout.LayoutParams).rightMargin = dp(8)
        })
        chip.addView(text(value, 13f, Neon.TEXT, selected))
        return chip
    }

    fun chipRow(chips: List<View>): HorizontalScrollView {
        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(16), dp(4), dp(16), dp(4)) }
        chips.forEach { row.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(42)).apply { rightMargin = dp(8) }) }
        return HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
            addView(row)
        }
    }

    fun sectionHeader(title: String, action: String? = null, onAction: (() -> Unit)? = null): View {
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(20), dp(22), dp(16), dp(10)) }
        row.addView(text(title, 19f, Neon.TEXT, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (action != null && onAction != null) {
            row.addView(text(action, 12.5f, Neon.ORANGE, true).apply {
                setPadding(dp(10), dp(6), dp(4), dp(6)); isClickable = true
                setOnClickListener { pop(this); onAction() }
            })
        }
        return row
    }

    fun empty(message: String) = text(message, 13f, Neon.MUTED, lines = 3).apply { setPadding(dp(20), dp(2), dp(20), dp(8)) }

    companion object {
        fun pop(view: View) {
            view.animate().cancel()
            view.animate().scaleX(.92f).scaleY(.92f).setDuration(70).withEndAction {
                view.animate().scaleX(1f).scaleY(1f).setDuration(260).setInterpolator(OvershootInterpolator(3f)).start()
            }.start()
        }
    }
}
