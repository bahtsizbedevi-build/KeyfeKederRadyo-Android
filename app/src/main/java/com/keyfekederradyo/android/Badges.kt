package com.keyfekederradyo.android

import android.content.Context

/** Small celebrations for listening habits. Everything is computed on the device. */
object Badges {
    data class Badge(val id: String, val title: String, val description: String, val icon: Int)

    data class Progress(val badge: Badge, val unlocked: Boolean, val current: Long, val target: Long, val unlockedAt: Long)

    val all = listOf(
        Badge("first_listen", "İlk frekans", "İlk radyonu dinledin", R.drawable.ic_radio_fill),
        Badge("first_fav", "Kalbini verdin", "İlk favorini ekledin", R.drawable.ic_heart),
        Badge("collector", "Koleksiyoncu", "10 radyoyu favorilere ekle", R.drawable.ic_heart),
        Badge("lucky", "Keyfine bıraktın", "Keyfime Bırak ile 10 radyo dinle", R.drawable.ic_shuffle),
        Badge("explorer", "Frekans kaşifi", "20 farklı radyo dinle", R.drawable.ic_explore_fill),
        Badge("traveler", "Şehir şehir", "5 farklı şehrin radyosunu dinle", R.drawable.ic_broadcast),
        Badge("night_owl", "Gece kuşu", "Gece yarısından sonra 30 dakika dinle", R.drawable.ic_moon),
        Badge("early_bird", "Erkenci kuş", "Sabah 5-8 arası 30 dakika dinle", R.drawable.ic_sun),
        Badge("marathon", "Maratoncu", "Bir günde 3 saat dinle", R.drawable.ic_timer),
        Badge("loyal", "Sadık dinleyici", "7 gün üst üste dinle", R.drawable.ic_sparkle),
    )

    fun progress(context: Context): List<Progress> {
        val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
        val days = ListeningStats.days(context)
        val (total, stations, cities) = ListeningStats.totals(days)
        val favorites = prefs.all.count { (k, v) -> k.startsWith("http") && v == true }.toLong()
        val values = mapOf(
            "first_listen" to (total to 1L),
            "first_fav" to (favorites to 1L),
            "collector" to (favorites to 10L),
            "lucky" to (prefs.getInt("pick_count", 0).toLong() to 10L),
            "explorer" to (stations.size.toLong() to 20L),
            "traveler" to (cities.count { it != "Ulusal" }.toLong() to 5L),
            "night_owl" to (days.sumOf { it.night } / 60 to 30L),
            "early_bird" to (days.sumOf { it.early } / 60 to 30L),
            "marathon" to ((days.maxOfOrNull { it.total } ?: 0L) / 60 to 180L),
            "loyal" to (ListeningStats.streak(days).toLong() to 7L),
        )
        return all.map { b ->
            val (current, target) = values.getValue(b.id)
            val at = prefs.getLong("badge_${b.id}", 0L)
            Progress(b, at > 0 || current >= target, current.coerceAtMost(target), target, at)
        }
    }

    /** Badges reached since the last check; they are stored so each one is celebrated once. */
    fun newlyUnlocked(context: Context): List<Badge> {
        val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
        val fresh = progress(context).filter { it.unlocked && it.unlockedAt == 0L }.map { it.badge }
        if (fresh.isNotEmpty()) {
            val edit = prefs.edit()
            fresh.forEach { edit.putLong("badge_${it.id}", System.currentTimeMillis()) }
            edit.apply()
        }
        return fresh
    }
}
