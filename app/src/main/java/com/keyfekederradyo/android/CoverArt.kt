package com.keyfekederradyo.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * Branded cover art for the media notification, lock screen, Android Auto and the widget:
 * the station logo on a white plate over a dark neon gradient with a glowing rim,
 * or the station's initials when it has no logo.
 */
object CoverArt {
    const val SCHEME = "keyfeart"

    fun uri(station: Station): Uri = Uri.Builder().scheme(SCHEME).authority("cover")
        .appendQueryParameter("name", station.name)
        .appendQueryParameter("logo", station.logoUrl)
        .build()

    /** [rounded] = transparent rounded corners (widget); square for the system media surfaces. */
    fun compose(context: Context, name: String, logo: Bitmap?, size: Int = 512, rounded: Boolean = false): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat()
        if (rounded) c.clipPath(android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, s, s), s * .2f, s * .2f, android.graphics.Path.Direction.CW) })
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        // deep backdrop with brand light from the top-left and bottom-right
        p.shader = LinearGradient(0f, 0f, s, s, intArrayOf(0xFF2B1208.toInt(), 0xFF1A0B16.toInt(), 0xFF0A0810.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, p)
        p.shader = RadialGradient(s * .2f, s * .15f, s * .8f, 0x99FF7A1A.toInt(), 0x00FF7A1A, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, p)
        p.shader = RadialGradient(s * .95f, s * .9f, s * .7f, 0x77FF2E88, 0x00FF2E88, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, s, s, p)
        p.shader = null

        // glowing neon rim
        val rim = RectF(s * .06f, s * .06f, s * .94f, s * .94f)
        val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = s * .018f
            maskFilter = BlurMaskFilter(s * .03f, BlurMaskFilter.Blur.NORMAL)
            shader = LinearGradient(0f, 0f, s, s, Neon.ORANGE, Neon.PINK, Shader.TileMode.CLAMP)
        }
        c.drawRoundRect(rim, s * .16f, s * .16f, glow)
        glow.maskFilter = null; glow.strokeWidth = s * .008f
        c.drawRoundRect(rim, s * .16f, s * .16f, glow)

        if (logo != null) {
            val plate = RectF(s * .24f, s * .24f, s * .76f, s * .76f)
            p.color = 0x55000000
            p.maskFilter = BlurMaskFilter(s * .04f, BlurMaskFilter.Blur.NORMAL)
            c.drawRoundRect(RectF(plate.left, plate.top + s * .02f, plate.right, plate.bottom + s * .02f), s * .1f, s * .1f, p)
            p.maskFilter = null; p.color = 0xFFFFFFFF.toInt()
            c.drawRoundRect(plate, s * .1f, s * .1f, p)
            val fit = plate.width() * .8f
            val scale = min(fit / logo.width, fit / logo.height)
            val w = logo.width * scale; val h = logo.height * scale
            c.drawBitmap(logo, null, Rect((s / 2 - w / 2).toInt(), (s / 2 - h / 2).toInt(), (s / 2 + w / 2).toInt(), (s / 2 + h / 2).toInt()), p)
        } else {
            p.color = 0xFFFFFFFF.toInt(); p.textAlign = Paint.Align.CENTER
            p.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            val initials = StationArtworkView.initialsOf(name)
            p.textSize = s * (if (initials.length > 1) .3f else .38f)
            p.setShadowLayer(s * .04f, 0f, s * .01f, 0xAAFF2E88.toInt())
            c.drawText(initials, s / 2, s / 2 - (p.descent() + p.ascent()) / 2, p)
            p.clearShadowLayer()
        }
        return out
    }
}

/**
 * Media3 bitmap loader: "keyfeart://cover?name=..&logo=.." URIs become branded covers,
 * everything else is loaded normally.
 */
@OptIn(UnstableApi::class)
class CoverArtBitmapLoader(private val context: Context) : BitmapLoader {
    private val fallback = DataSourceBitmapLoader(context)
    private val executor = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor())

    override fun supportsMimeType(mimeType: String) = fallback.supportsMimeType(mimeType)
    override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = fallback.decodeBitmap(data)

    override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> {
        if (uri.scheme != CoverArt.SCHEME) return fallback.loadBitmap(uri)
        val name = uri.getQueryParameter("name").orEmpty()
        val logoUrl = uri.getQueryParameter("logo").orEmpty()
        return executor.submit<Bitmap> {
            val logo = if (logoUrl.isBlank()) null else runCatching {
                java.net.URL(logoUrl).openStream().use { BitmapFactory.decodeStream(it) }
            }.getOrNull()
            CoverArt.compose(context, name, logo)
        }.let { Futures.catching(it, Throwable::class.java, { CoverArt.compose(context, name, null) }, MoreExecutors.directExecutor()) }
    }
}
