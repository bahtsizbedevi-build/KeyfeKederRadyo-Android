package com.keyfekederradyo.android

import android.animation.ValueAnimator
import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * "Keyfime bırak" bottom sheet: covers spin like a slot reel, slow down and land on the pick.
 * Mood chips re-roll within that mood; "Başka bir tane" re-rolls excluding the current pick.
 */
class PickSheetView(
    context: Context,
    private val pool: () -> List<Station>,
    private val pick: (mood: String?, exclude: String?) -> StationPicker.Pick?,
    private val onListen: (Station) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {
    private val ui = Ui(context)
    private val sheet = LinearLayout(context)
    private val reel = StationArtworkView(context)
    private val name = ui.text("", 24f, Neon.TEXT, true).apply { gravity = Gravity.CENTER }
    private val meta = ui.text("", 12.5f, Neon.MUTED).apply { gravity = Gravity.CENTER }
    private val reason = ui.text("", 13.5f, Neon.TEXT, lines = 2).apply { gravity = Gravity.CENTER }
    private val listen = ui.text("ŞİMDİ DİNLE", 15f, Neon.TEXT, true).apply { gravity = Gravity.CENTER; letterSpacing = .08f }
    private val moods = listOf(null to "Fark etmez", "Sakin" to "Sakin", "Enerjik" to "Enerjik", "Dertli" to "Dertli", "Yoldayım" to "Yoldayım", "Kafamı dinliyorum" to "Kafamı dinliyorum")
    private var mood: String? = null
    private var current: StationPicker.Pick? = null
    private var spinner: ValueAnimator? = null
    private lateinit var moodRow: HorizontalScrollView

    init {
        isClickable = true
        setBackgroundColor(0xB3000000.toInt())
        setOnClickListener { close() }

        sheet.apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(22), ui.dp(12), ui.dp(22), ui.dp(26))
            background = GlassDrawable(context, 32f, 0xF2121019.toInt(), Neon.STROKE, Neon.withAlpha(Neon.ORANGE, 70))
            isClickable = true // swallow taps so they don't close the sheet
        }
        sheet.addView(View(context).apply { background = ui.glass(3f, fill = 0x55FFFFFF) }, LinearLayout.LayoutParams(ui.dp(44), ui.dp(5)).apply { bottomMargin = ui.dp(16) })
        sheet.addView(ui.label("Keyfime bırak").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        sheet.addView(ui.text("Senin için bir frekans çeviriyorum", 13f, Neon.MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        sheet.addView(reel, LinearLayout.LayoutParams(ui.dp(172), ui.dp(172)).apply { topMargin = ui.dp(20); bottomMargin = ui.dp(18) })
        sheet.addView(name, LinearLayout.LayoutParams(-1, -2))
        sheet.addView(meta, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        sheet.addView(reason, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12); bottomMargin = ui.dp(16) })
        moodRow = buildMoods()
        sheet.addView(moodRow, LinearLayout.LayoutParams(-1, -2).apply { setMargins(-ui.dp(22), 0, -ui.dp(22), ui.dp(16)) })

        listen.background = GlassDrawable(context, 26f, Neon.ORANGE, 0x55FFFFFF, Neon.withAlpha(Neon.PINK, 150), intArrayOf(Neon.ORANGE, Neon.PINK))
        listen.isClickable = true
        listen.setOnClickListener { Ui.pop(it); current?.let { p -> onListen(p.station); close() } }
        sheet.addView(listen, LinearLayout.LayoutParams(-1, ui.dp(56)))

        val again = LinearLayout(context).apply {
            gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { Ui.pop(this); roll() }
        }
        again.addView(ui.icon(R.drawable.ic_dice, Neon.ORANGE, 20).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(8) })
        again.addView(ui.text("Başka bir tane", 14f, Neon.TEXT, true))
        sheet.addView(again, LinearLayout.LayoutParams(-1, ui.dp(52)).apply { topMargin = ui.dp(6) })

        addView(sheet, LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun buildMoods(): HorizontalScrollView {
        val chips = moods.map { (value, label) -> ui.chip(label, selected = value == mood) { mood = value; refreshMoods(); roll() } }
        return ui.chipRow(chips)
    }

    private fun refreshMoods() {
        val index = sheet.indexOfChild(moodRow)
        val lp = moodRow.layoutParams
        sheet.removeView(moodRow)
        moodRow = buildMoods()
        sheet.addView(moodRow, index, lp)
    }

    /** Spins through random covers, decelerating, then lands on a fresh pick. */
    fun roll() {
        val result = pick(mood, current?.station?.resolvedUrl)
        if (result == null) {
            name.text = "Uygun radyo bulamadım"; meta.text = ""; reason.text = "Başka bir mod seçmeyi dene."
            return
        }
        val stations = pool().filter { it.logoUrl.isNotBlank() }.ifEmpty { pool() }
        spinner?.cancel()
        listen.isEnabled = false; listen.alpha = .5f
        reason.animate().alpha(0f).setDuration(120).start()
        var lastStep = -1
        spinner = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1300; interpolator = DecelerateInterpolator(2.2f)
            addUpdateListener {
                val step = ((it.animatedValue as Float) * 16).toInt()
                if (step != lastStep && step < 16 && stations.isNotEmpty()) {
                    lastStep = step
                    val s = stations.random()
                    reel.bind(s.name, s.genre, s.logoUrl)
                    name.text = s.name; meta.text = s.genre
                    reel.scaleX = .9f; reel.scaleY = .9f
                    reel.animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }
            }
            doOnEnd { land(result) }
            start()
        }
    }

    private fun land(result: StationPicker.Pick) {
        current = result
        val s = result.station
        reel.bind(s.name, s.genre, s.logoUrl)
        reel.setPlaying(true)
        reel.scaleX = .8f; reel.scaleY = .8f
        reel.animate().scaleX(1f).scaleY(1f).setDuration(420).setInterpolator(OvershootInterpolator(2.5f)).start()
        name.text = s.name
        meta.text = listOf(s.genre, s.country).filter { it.isNotBlank() }.joinToString(" • ")
        reason.text = result.reason
        reason.animate().alpha(1f).setDuration(240).start()
        listen.isEnabled = true; listen.alpha = 1f
    }

    fun show(parent: ViewGroup) {
        parent.addView(this, ViewGroup.LayoutParams(-1, -1))
        alpha = 0f; animate().alpha(1f).setDuration(200).start()
        sheet.translationY = ui.dpf(500f)
        sheet.animate().translationY(0f).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
        roll()
    }

    fun close() {
        spinner?.cancel()
        reel.setPlaying(false)
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(240).start()
        animate().alpha(0f).setDuration(240).withEndAction { (parent as? ViewGroup)?.removeView(this); onClose() }.start()
    }

    private fun ValueAnimator.doOnEnd(block: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        private var cancelled = false
        override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
        override fun onAnimationEnd(animation: android.animation.Animator) { if (!cancelled) block() }
    })
}
