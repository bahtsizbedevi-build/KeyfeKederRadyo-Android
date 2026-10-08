package com.keyfekederradyo.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import kotlin.math.min
import kotlin.math.sin

/**
 * Station cover: real logo when one loads, otherwise a genre-tinted gradient with the station's initials.
 * While playing, the rim glows and pulses.
 */
class StationArtworkView(context: Context) : View(context) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clip = Path()
    private val rect = RectF()
    private val dst = Rect()

    private var initials = ""
    private var accent = 0xFFFF7A00.toInt()
    private var logo: Bitmap? = null
    private var brand: Bitmap? = null
    private var boundUrl = ""
    private var active = false
    private var phase = 0f

    init { setLayerType(LAYER_TYPE_SOFTWARE, null) } // BlurMaskFilter needs a software layer

    fun bind(name: String, genreValue: String, artworkUrl: String = "") {
        initials = initialsOf(name)
        accent = accentFor(genreValue)
        if (name == "RADYO" && artworkUrl.isBlank()) {
            brand = brand ?: BitmapFactory.decodeResource(resources, R.drawable.keyfe_keder_brand)
        } else brand = null
        if (artworkUrl != boundUrl) {
            boundUrl = artworkUrl
            logo = StationImageLoader.cached(artworkUrl)
            if (logo == null && artworkUrl.isNotBlank()) {
                StationImageLoader.load(artworkUrl) { bmp -> if (boundUrl == artworkUrl) { logo = bmp; invalidate() } }
            }
        }
        invalidate()
    }

    fun setPlaying(playing: Boolean) {
        if (active == playing) return
        active = playing
        if (playing) postInvalidateOnAnimation() else invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val radius = min(w, h) * .2f
        val inset = if (active) min(w, h) * .045f else 0f
        rect.set(inset, inset, w - inset, h - inset)
        val pulse = if (active) (0.5f + 0.5f * sin(phase.toDouble())).toFloat() else 0f

        if (active) {
            glowPaint.color = Neon.ORANGE
            glowPaint.alpha = (110 + 110 * pulse).toInt()
            glowPaint.maskFilter = BlurMaskFilter(inset * 1.6f + 1f, BlurMaskFilter.Blur.OUTER)
            canvas.drawRoundRect(rect, radius, radius, glowPaint)
        }

        clip.reset(); clip.addRoundRect(rect, radius, radius, Path.Direction.CW)
        canvas.save(); canvas.clipPath(clip)

        val dark = blend(accent, Color.BLACK, .78f)
        bgPaint.shader = LinearGradient(rect.left, rect.top, rect.right, rect.bottom, blend(accent, Color.BLACK, .35f), dark, Shader.TileMode.CLAMP)
        canvas.drawRect(rect, bgPaint)
        bgPaint.shader = RadialGradient(rect.left + rect.width() * .25f, rect.top + rect.height() * .2f, rect.width() * .9f,
            0x55FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(rect, bgPaint)

        val art = logo ?: brand
        if (art != null) {
            // Logos sit on a soft light plate so dark and transparent logos stay readable
            val plate = min(rect.width(), rect.height()) * (if (logo != null) .66f else .92f)
            val cx = rect.centerX(); val cy = rect.centerY()
            if (logo != null) {
                bgPaint.shader = null; bgPaint.color = 0xF2FFFFFF.toInt()
                canvas.drawRoundRect(cx - plate / 2, cy - plate / 2, cx + plate / 2, cy + plate / 2, plate * .22f, plate * .22f, bgPaint)
            }
            val fit = plate * (if (logo != null) .82f else 1f)
            val scale = min(fit / art.width, fit / art.height)
            val bw = art.width * scale; val bh = art.height * scale
            dst.set((cx - bw / 2).toInt(), (cy - bh / 2).toInt(), (cx + bw / 2).toInt(), (cy + bh / 2).toInt())
            canvas.drawBitmap(art, null, dst, bitmapPaint)
        } else {
            textPaint.textSize = min(rect.width(), rect.height()) * (if (initials.length > 1) .34f else .42f)
            textPaint.setShadowLayer(textPaint.textSize * .18f, 0f, textPaint.textSize * .06f, 0x66000000)
            val y = rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            canvas.drawText(initials, rect.centerX(), y, textPaint)
        }
        canvas.restore()

        rimPaint.strokeWidth = maxOf(1.5f, w * .012f)
        rimPaint.color = if (active) Neon.ORANGE else 0x33FFFFFF
        rimPaint.alpha = if (active) (150 + 105 * pulse).toInt() else 60
        canvas.drawRoundRect(rect, radius, radius, rimPaint)

        if (active) {
            phase += .09f
            postInvalidateOnAnimation()
        }
    }

    companion object {
        fun accentFor(genre: String): Int {
            val g = genre.lowercase()
            return when {
                "rock" in g -> 0xFFFF5A3D.toInt()
                "arabesk" in g || "slow" in g -> 0xFFE8457D.toInt()
                "jazz" in g || "lounge" in g -> 0xFF7C6CFF.toInt()
                "classical" in g || "klasik" in g -> 0xFFE8B23A.toInt()
                "elek" in g || "elect" in g || "disco" in g || "dance" in g -> 0xFF19C9B6.toInt()
                "rap" in g || "hip" in g -> 0xFF9B5CFF.toInt()
                "halk" in g || "folk" in g || "türkü" in g -> 0xFF4CAF6A.toInt()
                "90" in g || "80" in g || "oldies" in g || "nostalji" in g -> 0xFFFF9E3D.toInt()
                "haber" in g || "news" in g -> 0xFF3D8BFF.toInt()
                "pop" in g -> 0xFFFF6B35.toInt()
                else -> 0xFFFF7A00.toInt()
            }
        }

        fun initialsOf(name: String): String {
            val skip = setOf("radyo", "radio", "fm", "turkey", "türkiye", "turkiye", "the")
            val words = name.split(Regex("[\\s\\-_.()]+")).filter { it.isNotBlank() && it.lowercase() !in skip }
            val source = words.ifEmpty { name.split(" ").filter { it.isNotBlank() } }
            return source.take(2).joinToString("") { it.first().uppercase() }.ifBlank { "K" }
        }

        private fun blend(a: Int, b: Int, t: Float): Int = Color.rgb(
            (Color.red(a) * (1 - t) + Color.red(b) * t).toInt(),
            (Color.green(a) * (1 - t) + Color.green(b) * t).toInt(),
            (Color.blue(a) * (1 - t) + Color.blue(b) * t).toInt()
        )
    }
}
