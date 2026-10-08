package com.keyfekederradyo.android

import android.content.Context
import android.net.Uri
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Station list sources, freshest first:
 * 1. data/stations.json in the app's GitHub repo (lets us fix streams without an app update)
 * 2. the last list downloaded successfully, cached on the device
 * 3. the copy bundled into the APK at build time (always available, also offline)
 */
class StationRepository(private val context: Context) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val sourceUrl =
        "https://raw.githubusercontent.com/bahtsizbedevi-build/KeyfeKederRadyo-Android/main/data/stations.json"
    private val cacheFile get() = File(context.filesDir, "stations.json")

    /** Instant list from the device cache, or the bundled copy. Never touches the network. */
    fun loadLocal(): List<Station> {
        // a cache written before this app version was installed is older than the bundled list
        val installed = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (cacheFile.exists() && cacheFile.lastModified() < installed) cacheFile.delete()
        runCatching { cacheFile.readText() }.getOrNull()
            ?.let { cached -> runCatching { parse(cached) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it } }
        return parse(context.assets.open("stations.json").bufferedReader().use { it.readText() })
    }

    /** Downloads the latest list and caches it. Throws when offline or the file is invalid. */
    fun refresh(): List<Station> {
        val request = Request.Builder().url(sourceUrl).get().build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val parsed = parse(body)
            if (parsed.isEmpty()) error("empty station list")
            runCatching { cacheFile.writeText(body) }
            return parsed
        }
    }

    private fun parse(json: String): List<Station> {
        val array = JSONArray(json)
        val result = ArrayList<Station>(array.length())
        val seen = HashSet<String>()

        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val name = o.optString("name").trim()
            val url = o.optString("url_resolved").ifBlank { o.optString("url") }.trim()
            if (name.isBlank() || url.isBlank()) continue

            val key = url.lowercase(Locale.ROOT)
            if (!seen.add(key)) continue

            val homepage = o.optString("homepage").trim()
            val rawGenre = o.optString("genre").trim()
            val fallbackText = buildString {
                append(name).append(' ')
                append(rawGenre).append(' ')
                append(o.optString("tags")).append(' ')
                append(o.optString("language"))
            }
            val genre = normalizeGenre(rawGenre, fallbackText)

            result += Station(
                name = name,
                url = o.optString("url"),
                resolvedUrl = url,
                genre = genre,
                language = o.optString("language").trim(),
                country = o.optString("country").trim(),
                quality = o.optString("quality").trim(),
                song = o.optString("song").ifBlank { "Canlı yayın" },
                homepage = homepage,
                logoUrl = o.optString("logo").trim().ifBlank { faviconFor(homepage) },
                city = o.optString("city").trim()
            )
        }

        return result.sortedWith(
            compareBy<Station> { it.name.lowercase(Locale.ROOT) }
        )
    }

    private fun faviconFor(homepage: String): String {
        val host = runCatching { Uri.parse(homepage).host.orEmpty().removePrefix("www.") }.getOrDefault("")
        return if (host.isNotBlank() && "streamtheworld" !in host && "radyotvonline" !in host) {
            "https://www.google.com/s2/favicons?domain=$host&sz=128"
        } else ""
    }

    private fun normalizeGenre(raw: String, source: String): String {
        if (raw.isNotBlank()) return raw.split(',').first().trim()
        val text = source.lowercase(Locale.ROOT)
        return when {
            "arabesk" in text || "fantazi" in text || "damar" in text -> "Arabesk"
            "rock" in text -> "Rock"
            "jazz" in text -> "Jazz"
            "classical" in text || "klasik" in text -> "Klasik"
            "lounge" in text || "chill" in text -> "Lounge"
            "dance" in text || "electro" in text || "electronic" in text -> "Elektronik"
            "pop" in text -> "Pop"
            "folk" in text || "türk halk" in text || "turku" in text -> "Türk Halk"
            "news" in text || "haber" in text -> "Haber"
            "oldies" in text || "80s" in text || "90s" in text -> "Nostalji"
            else -> "Radyo"
        }
    }
}
