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
    private val onSearch: (platform: String) -> Unit,
    private val onShare: () -> Unit,
) : FrameLayout(context) {
    private val ui = Ui(context)
    private val shade = Paint()
    private var accent = Neon.ORANGE

    private val artwork = StationArtworkView(context)
    private val songView = ui.text("", 25f, Neon.TEXT, true).apply {
        gravity = Gravity.CENTER; isSingleLine = true; ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
    }
    private val artistView = ui.text("", 15f, Neon.MUTED).apply { gravity = Gravity.CENTER }
    private val liveBadge = ui.label("● Canlı yayın").apply { gravity = Gravity.CENTER }
    private val stationChip = ui.text("", 12.5f, Neon.TEXT, true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(14), ui.dp(7), ui.dp(14), ui.dp(7)) }
    private val spectrum = AudioSpectrumView(context, full = true)
    private val favButton = ui.iconButton(R.drawable.ic_heart_outline, "Favorilere ekle", 48, 22) { onFavorite() }
    private val playIcon = ImageView(context).apply { setColorFilter(0xFFFFFFFF.toInt()); scaleType = ImageView.ScaleType.CENTER_INSIDE }
    private val playButton = FrameLayout(context)
    private val sleepText = ui.text("Uyku", 11f, Neon.MUTED).apply { gravity = Gravity.CENTER }
    private val songsBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
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

        // top bar
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(ui.iconButton(R.drawable.ic_chevron_down, "Kapat", 48, 26) { onClose() })
        top.addView(ui.label("Şimdi çalıyor", Neon.TEXT).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(favButton)
        column.addView(top, LinearLayout.LayoutParams(-1, ui.dp(56)))

        column.addView(artwork, LinearLayout.LayoutParams(ui.dp(268), ui.dp(268)).apply { topMargin = ui.dp(18); bottomMargin = ui.dp(22) })
        column.addView(liveBadge, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(10) })
        column.addView(songView, LinearLayout.LayoutParams(-1, -2))
        column.addView(artistView, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) })
        column.addView(stationChip, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(14); gravity = Gravity.CENTER_HORIZONTAL })
        column.addView(spectrum, LinearLayout.LayoutParams(-1, ui.dp(132)).apply { topMargin = ui.dp(12) })

        // transport controls
        val controls = LinearLayout(context).apply { gravity = Gravity.CENTER }
        controls.addView(ui.iconButton(R.drawable.ic_prev, "Önceki radyo", 60, 28) { onPrev() })
        playButton.apply {
            contentDescription = "Çal / duraklat"; isClickable = true
            addView(playIcon, LayoutParams(ui.dp(34), ui.dp(34), Gravity.CENTER))
            setOnClickListener { Ui.pop(this); onToggle() }
        }
        controls.addView(playButton, LinearLayout.LayoutParams(ui.dp(86), ui.dp(86)).apply { setMargins(ui.dp(26), 0, ui.dp(26), 0) })
        controls.addView(ui.iconButton(R.drawable.ic_next, "Sonraki radyo", 60, 28) { onNext() })
        column.addView(controls, LinearLayout.LayoutParams(-1, ui.dp(110)).apply { topMargin = ui.dp(4) })

        // actions
        val actions = LinearLayout(context).apply { gravity = Gravity.CENTER }
        actions.addView(action(R.drawable.ic_moon, sleepText) { onSleep() })
        actions.addView(action(R.drawable.ic_music_search, ui.text("Spotify", 11f, Neon.MUTED)) { onSearch("spotify") })
        actions.addView(action(R.drawable.ic_video, ui.text("YouTube", 11f, Neon.MUTED)) { onSearch("youtube") })
        actions.addView(action(R.drawable.ic_share, ui.text("Paylaş", 11f, Neon.MUTED)) { onShare() })
        column.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) })

        column.addView(songsBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(18) })
    }

    private fun action(icon: Int, label: TextView, onClick: () -> Unit): View {
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { Ui.pop(this); onClick() }
        }
        box.addView(FrameLayout(context).apply {
            background = ui.glass(18f)
            addView(ui.icon(icon, Neon.TEXT, 22), LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ui.dp(54), ui.dp(54)))
        label.gravity = Gravity.CENTER
        box.addView(label, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(6) })
        return box.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }
    }

    fun bind(state: NowPlayingUi, recentSongs: List<SongHistory.Entry>) {
        val station = state.station ?: return
        accent = StationArtworkView.accentFor(station.genre)
        artwork.bind(station.name, station.genre, station.logoUrl)
        artwork.setPlaying(state.playing)
        spectrum.setAccent(accent)
        spectrum.setPlaying(state.playing)
        val hasSong = !state.song.isNullOrBlank()
        songView.text = if (hasSong) state.song else station.name
        artistView.text = when {
            state.error -> "Yayına ulaşılamadı, yeniden deneniyor…"
            state.buffering -> "Bağlanıyor…"
            hasSong -> state.artist?.takeIf { it.isNotBlank() && it != StationMedia.LIVE } ?: station.name
            else -> "Canlı yayın • ${station.genre.ifBlank { "Radyo" }}"
        }
        liveBadge.text = if (state.playing) "● CANLI YAYIN" else if (state.buffering) "BAĞLANIYOR" else "DURAKLATILDI"
        liveBadge.setTextColor(if (state.playing) accent else Neon.MUTED)
        stationChip.text = station.name
        stationChip.background = ui.glass(16f, Neon.withAlpha(accent, 60))
        (favButton.getChildAt(0) as ImageView).apply {
            setImageResource(if (state.favorite) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            setColorFilter(if (state.favorite) Neon.PINK else Neon.TEXT)
        }
        playIcon.setImageResource(if (state.playing || state.buffering) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.background = GlassDrawable(context, 43f, accent, 0x55FFFFFF, Neon.withAlpha(accent, if (state.playing) 200 else 90),
            intArrayOf(accent, Neon.mix(accent, Neon.PINK, .7f)))
        if (state.playing) { if (!pulse.isStarted) pulse.start() } else { pulse.cancel(); playButton.scaleX = 1f; playButton.scaleY = 1f }
        sleepText.text = state.sleepLabel ?: "Uyku"
        sleepText.setTextColor(if (state.sleepLabel != null) Neon.ORANGE else Neon.MUTED)
        bindSongs(station, recentSongs)
        invalidate()
    }

    private fun bindSongs(station: Station, songs: List<SongHistory.Entry>) {
        songsBox.removeAllViews()
        val here = songs.filter { it.station == station.name }.take(5)
        if (here.isEmpty()) return
        songsBox.addView(ui.label("Bu yayında az önce çalanlar", Neon.MUTED).apply { setPadding(ui.dp(4), 0, 0, ui.dp(8)) })
        here.forEach { e ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(10)); background = ui.glass(16f)
            }
            row.addView(ui.text(e.title, 14f, Neon.TEXT, true))
            row.addView(ui.text(listOf(e.artist, timeAgo(e.time)).filter { it.isNotBlank() }.joinToString(" • "), 12f, Neon.MUTED))
            songsBox.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
        }
    }

    override fun onDraw(canvas: Canvas) {
        // deep backdrop washed with the station colour
        shade.shader = LinearGradient(0f, 0f, 0f, height.toFloat(),
            intArrayOf(Neon.mix(Neon.BG, accent, .28f), Neon.mix(Neon.BG, Neon.PINK, .08f), Neon.BG), floatArrayOf(0f, .45f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), shade)
        super.onDraw(canvas)
    }

    // swipe down anywhere near the top to close
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
        alpha = 1f
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

    companion object {
        fun timeAgo(time: Long): String {
            val mins = ((System.currentTimeMillis() - time) / 60_000L).coerceAtLeast(0)
            return when {
                mins < 1 -> "az önce"
                mins < 60 -> "$mins dk önce"
                mins < 60 * 24 -> "${mins / 60} sa önce"
                else -> "${mins / (60 * 24)} gün önce"
            }
        }
    }
}
