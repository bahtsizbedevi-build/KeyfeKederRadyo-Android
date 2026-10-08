package com.keyfekederradyo.android

import android.content.Context
import org.json.JSONObject
import java.util.Calendar

/**
 * On-device listening diary (never leaves the phone): seconds listened per day, per station,
 * per genre and per city, plus night/early-morning time. Feeds the weekly summary and badges.
 */
object ListeningStats {
    private const val KEY = "listening_stats"
    private const val KEEP_DAYS = 60

    data class Day(
        val date: String,
        val total: Long,
        val stations: Map<String, Long>,
        val genres: Map<String, Long>,
        val cities: Map<String, Long>,
        val night: Long,
        val early: Long,
    )

    fun dateKey(cal: Calendar = Calendar.getInstance()) =
        "%04d-%02d-%02d".format(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH))

    /** Adds [seconds] of listening to [station], ending now. */
    @Synchronized
    fun add(context: Context, station: Station, seconds: Long) {
        if (seconds < 5) return
        val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
        val root = runCatching { JSONObject(prefs.getString(KEY, "{}")!!) }.getOrDefault(JSONObject())
        val now = Calendar.getInstance()
        val key = dateKey(now)
        val day = root.optJSONObject(key) ?: JSONObject()
        fun bump(obj: String, name: String) {
            val o = day.optJSONObject(obj) ?: JSONObject()
            o.put(name, o.optLong(name) + seconds); day.put(obj, o)
        }
        day.put("total", day.optLong("total") + seconds)
        bump("stations", station.resolvedUrl)
        bump("genres", station.genre.ifBlank { "Radyo" })
        if (station.city.isNotBlank()) bump("cities", station.city)
        when (now.get(Calendar.HOUR_OF_DAY)) {
            in 0..4 -> day.put("night", day.optLong("night") + seconds)
            in 5..7 -> day.put("early", day.optLong("early") + seconds)
        }
        root.put(key, day)
        // forget days older than KEEP_DAYS
        val cutoff = dateKey((now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -KEEP_DAYS) })
        root.keys().asSequence().toList().filter { it < cutoff }.forEach { root.remove(it) }
        prefs.edit().putString(KEY, root.toString()).apply()
    }

    fun days(context: Context): List<Day> {
        val raw = context.getSharedPreferences("radio", Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        val root = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        fun map(o: JSONObject?) = o?.keys()?.asSequence()?.associateWith { o.optLong(it) }.orEmpty()
        return root.keys().asSequence().map { k ->
            val d = root.getJSONObject(k)
            Day(k, d.optLong("total"), map(d.optJSONObject("stations")), map(d.optJSONObject("genres")),
                map(d.optJSONObject("cities")), d.optLong("night"), d.optLong("early"))
        }.sortedBy { it.date }.toList()
    }

    /** Consecutive days (ending today or yesterday) with at least a minute of listening. */
    fun streak(days: List<Day>, today: Calendar = Calendar.getInstance()): Int {
        val listened = days.filter { it.total >= 60 }.map { it.date }.toSet()
        val cal = today.clone() as Calendar
        if (dateKey(cal) !in listened) cal.add(Calendar.DAY_OF_YEAR, -1)
        var n = 0
        while (dateKey(cal) in listened) { n++; cal.add(Calendar.DAY_OF_YEAR, -1) }
        return n
    }

    data class Week(
        val seconds: Long,
        val topStationUrl: String?,
        val topGenre: String?,
        val stationCount: Int,
        val activeDays: Int,
        val nightSeconds: Long,
        val earlySeconds: Long,
        val persona: String,
    )

    /** The last 7 days, summarised with a friendly "listener type". */
    fun week(days: List<Day>, today: Calendar = Calendar.getInstance()): Week {
        val from = dateKey((today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -6) })
        val recent = days.filter { it.date >= from }
        fun merge(pick: (Day) -> Map<String, Long>) = recent.flatMap { pick(it).entries }.groupBy({ it.key }, { it.value }).mapValues { it.value.sum() }
        val stations = merge { it.stations }
        val genres = merge { it.genres }
        val total = recent.sumOf { it.total }
        val night = recent.sumOf { it.night }
        val early = recent.sumOf { it.early }
        val topGenre = genres.maxByOrNull { it.value }?.key
        val persona = when {
            total == 0L -> "Yeni dinleyici"
            night > total / 4 -> "Gece kuşu"
            early > total / 4 -> "Erkenci kuş"
            stations.size >= 10 -> "Frekans kaşifi"
            stations.size <= 2 && total > 3600 -> "Sadık dinleyici"
            topGenre != null -> "$topGenre tutkunu"
            else -> "Keyif ehli"
        }
        return Week(total, stations.maxByOrNull { it.value }?.key, topGenre, stations.size, recent.count { it.total >= 60 }, night, early, persona)
    }

    fun totals(days: List<Day>): Triple<Long, Set<String>, Set<String>> =
        Triple(days.sumOf { it.total }, days.flatMap { it.stations.keys }.toSet(), days.flatMap { it.cities.keys }.toSet())

    fun formatDuration(seconds: Long): String {
        val h = seconds / 3600; val m = (seconds % 3600) / 60
        return when {
            h > 0 && m > 0 -> "$h sa $m dk"
            h > 0 -> "$h saat"
            m > 0 -> "$m dakika"
            else -> "birkaç saniye"
        }
    }
}
