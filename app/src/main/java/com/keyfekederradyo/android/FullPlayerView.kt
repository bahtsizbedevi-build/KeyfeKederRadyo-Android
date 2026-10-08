package com.keyfekederradyo.android

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** What the player screens show; built by MainActivity from the media controller. */
data class NowPlayingUi(
    val station: Station?,
    val song: String?,
    val artist: String?,
    val playing: Boolean,
    val buffering: Boolean,
    val error: Boolean,
    val favorite: Boolean,
    val sleepLabel: String?,
)

/** Full-screen "Şimdi çalıyor" overlay. Slides up from the mini player; swipe down or back to close. */
class FullPlayerView(
    context: Context,
    private val onClose: () -> Unit,
    private val onToggle: () -> Unit,
    private val onPrev: () -> Unit,
    private val onNext: () -> Unit,
    private val onFavorite: () -> Unit,
    private val onSleep: () -> Unit,
    private val onShare: () -> Unit,
) : FrameLayout(context) {
    private val ui = Ui(context)
    private val shade = Paint()

    private val artwork = StationArtworkView(context)
    private val songView = ui.text("", 25f, Neon.TEXT, true).apply {
        gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
    }
    private val artistView = ui.text("", 15f, Neon.MUTED).apply { gravity = Gravity.CENTER }
    private val liveBadge = ui.label("● Canlı yayın").apply { gravity = Gravity.CENTER }
    private val stationChip = ui.text("", 12.5f, Neon.TEXT, true).apply {
        gravity = Gravity.CENTER; setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(8))
    }
    private val spectrum = AudioSpectrumView(context, full = true)
    private val favorite = FavoriteButton(context, 24f).apply { setOnClickListener { onFavorite() } }
    private val playIcon = ImageView(context).apply { setColorFilter(Neon.TEXT); scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val playButton = FrameLayout(context)
    private val sleepText = ui.text("Uyku", 11.5f, Neon.MUTED).apply { gravity = Gravity.CENTER }
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600; repeatCount = ValueAnimator.INFINITE; repeatMode = ValueAnimator.REVERSE
        addUpdateListener { val v = it.animatedValue as Float; playButton.scaleX = 1f + .04f * v; playButton.scaleY = 1f + .04f * v }
    }
    private var downY = 0f

    init {
        isClickable = true
        setWillNotDraw(false)
        val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER }
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(22), ui.dp(8), ui.dp(22), ui.dp(28))
        }
        scroll.addView(column)
        addView(scroll, LayoutParams(-1, -1))

        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.iconButton(R.drawable.ic_chevron_down, "Kapat", 48, 22) { onClose() })
        top.addView(ui.label("Şimdi çalıyor", Neon.TEXT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(FrameLayout(context).apply {
            background = android.graphics.drawable.GradientDrawable().apply { shape = android.graphics.drawable.GradientDrawable.OVAL; setColor(Neon.GLASS) }
            addView(favorite, LayoutParams(-1, -1))
        }, LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)))
        column.addView(top, LinearLayout.LayoutParams(-1, ui.dp(56)))

        column.addView(artwork, LinearLayout.LayoutParams(ui.dp(272), ui.dp(272)).apply { topMargin = ui.dp(20); bottomMargin = ui.dp(24) })
        column.addView(liveBadge, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        column.addView(songView, LinearLayout.LayoutParams(-1, -2))
        column.addView(artistView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        column.addView(stationChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(14); gravity = Gravity.CENTER_HORIZONTAL })
        column.addView(spectrum, LinearLayout.LayoutParams(-1, ui.dp(132)).apply { topMargin = ui.dp(12) })

        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
        controls.addView(ui.iconButton(R.drawable.ic_prev, "Önceki radyo", 60, 26) { onPrev() })
        playButton.apply {
            contentDescription = "Çal / duraklat"; isClickable = true
            addView(playIcon, LayoutParams(ui.dp(34), ui.dp(34), Gravity.CENTER))
            setOnClickListener { Ui.pop(this); onToggle() }
        }
        controls.addView(playButton, LinearLayout.LayoutParams(ui.dp(88), ui.dp(88)).apply { setMargins(ui.dp(26), 0, ui.dp(26), 0) })
        controls.addView(ui.iconButton(R.drawable.ic_next, "Sonraki radyo", 60, 26) { onNext() })
        column.addView(controls, LinearLayout.LayoutParams(-1, ui.dp(112)).apply { topMargin = ui.dp(4) })

        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER }
        actions.addView(action(R.drawable.ic_timer, sleepText) { onSleep() })
        actions.addView(action(R.drawable.ic_share, ui.text("Paylaş", 11.5f, Neon.MUTED)) { onShare() })
        column.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
    }

    private fun action(icon: Int, label: TextView, onClick: () -> Unit): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { Ui.pop(this); onClick() }
        }
        box.addView(FrameLayout(context).apply {
            background = ui.glass(20f)
            addView(ui.icon(icon, Neon.TEXT, 24), LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ui.dp(58), ui.dp(58)))
        label.gravity = Gravity.CENTER
        box.addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(6) })
        return box.apply { layoutParams = LinearLayout.LayoutParams(ui.dp(110), -2) }
    }

    /** [animateFavorite] = the user just tapped the heart. */
    fun bind(state: NowPlayingUi, animateFavorite: Boolean = false) {
        val station = state.station ?: return
        artwork.bind(station.name, station.genre, station.logoUrl)
        artwork.setPlaying(state.playing)
        spectrum.setPlaying(state.playing)
        val hasSong = !state.song.isNullOrBlank()
        songView.text = if (hasSong) state.song else station.name
        artistView.text = when {
            state.error -> "Yayına ulaşılamadı, yeniden deneniyor…"
            state.buffering -> "Bağlanıyor…"
            hasSong -> state.artist?.takeIf { it.isNotBlank() && it != StationMedia.LIVE } ?: "Canlı yayın"
            else -> "Canlı yayın"
        }
        liveBadge.text = if (state.playing) "● CANLI YAYIN" else if (state.buffering) "BAĞLANIYOR" else "DURAKLATILDI"
        liveBadge.setTextColor(if (state.playing) Neon.ORANGE else Neon.MUTED)
        stationChip.text = listOf(station.name, station.genre).filter { it.isNotBlank() }.joinToString("  •  ")
        stationChip.background = ui.glass(18f)
        if (animateFavorite) favorite.animateTo(state.favorite) else if (favorite.isOn != state.favorite) favorite.setOn(state.favorite)
        playIcon.setImageResource(if (state.playing || state.buffering) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.background = GlassDrawable(context, 44f, Neon.ORANGE, 0x55FFFFFF, Neon.withAlpha(Neon.ORANGE, if (state.playing) 190 else 90), Neon.BRAND)
        if (state.playing) { if (!pulse.isStarted) pulse.start() } else { pulse.cancel(); playButton.scaleX = 1f; playButton.scaleY = 1f }
        sleepText.text = state.sleepLabel ?: "Uyku"
        sleepText.setTextColor(if (state.sleepLabel != null) Neon.ORANGE else Neon.MUTED)
    }

    override fun onDraw(canvas: Canvas) {
        shade.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(0xFF2A140B.toInt(), 0xFF170B14.toInt(), Neon.BG), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        super.onDraw(canvas)
    }

    // swipe down from the upper half to close
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> downY = ev.rawY
            MotionEvent.ACTION_MOVE -> if (ev.rawY - downY > ui.dp(24) && downY < height * .45f) return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> translationY = (ev.rawY - downY).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (translationY > height * .18f) onClose()
                else animate().translationY(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            }
        }
        return true
    }

    fun show(parent: ViewGroup) {
        if (this.parent == null) parent.addView(this, ViewGroup.LayoutParams(-1, -1))
        translationY = parent.height.toFloat().takeIf { it > 0 } ?: 2000f
        animate().translationY(0f).setDuration(380).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    fun hide(after: () -> Unit) {
        pulse.cancel()
        spectrum.setPlaying(false); artwork.setPlaying(false)
        animate().translationY(height.toFloat()).setDuration(260).setInterpolator(DecelerateInterpolator()).withEndAction {
            (parent as? ViewGroup)?.removeView(this); translationY = 0f; after()
        }.start()
    }

    override fun onDetachedFromWindow() { pulse.cancel(); super.onDetachedFromWindow() }
}
