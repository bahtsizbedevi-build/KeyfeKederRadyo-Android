package com.keyfekederradyo.android

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Small logo loader: memory LRU + HttpResponseCache on disk (installed in MainActivity). */
object StationImageLoader {
    private const val MAX_SIZE_PX = 256
    private val cache = LruCache<String, Bitmap>(96)
    private val failed = HashSet<String>()
    private val executor = Executors.newFixedThreadPool(4)
    private val main = Handler(Looper.getMainLooper())

    fun cached(url: String): Bitmap? = synchronized(cache) { cache.get(url) }

    /** Calls [onLoaded] on the main thread with the bitmap, or null when it can't be loaded. */
    fun load(url: String, onLoaded: (Bitmap?) -> Unit) {
        if (url.isBlank()) { onLoaded(null); return }
        cached(url)?.let { onLoaded(it); return }
        if (synchronized(failed) { url in failed }) { onLoaded(null); return }

        executor.execute {
            val bitmap = runCatching { download(url) }.getOrNull()
            if (bitmap != null) synchronized(cache) { cache.put(url, bitmap) }
            else synchronized(failed) { failed += url }
            main.post { onLoaded(bitmap) }
        }
    }

    private fun download(url: String): Bitmap? {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 7000
            connection.instanceFollowRedirects = true
            connection.useCaches = true
            connection.setRequestProperty("User-Agent", "KeyfeKederRadyo/1.0")
            if (connection.responseCode !in 200..299) return null
            val bytes = connection.inputStream.use { it.readBytes() }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth < 32 || bounds.outHeight < 32) return null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_SIZE_PX && bounds.outHeight / (sample * 2) >= MAX_SIZE_PX) sample *= 2
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            connection.disconnect()
        }
    }
}
