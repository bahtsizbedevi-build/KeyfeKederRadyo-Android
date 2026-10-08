package com.keyfekederradyo.android

import kotlin.random.Random

/**
 * "Keyfime bırak": picks a station the listener is likely to enjoy right now.
 * Score = mood match (explicit mood, or one inferred from the time of day)
 *       + affinity to the genres of their favourites
 *       + a little randomness,
 * never one of the last few played, never a stream that failed recently.
 */
object StationPicker {
    data class Pick(val station: Station, val reason: String, val mood: String)

    fun moodForHour(hour: Int): String = when (hour) {
        in 0..5 -> "Kafamı dinliyorum"
        in 6..9 -> "Enerjik"
        in 10..16 -> "Yoldayım"
        in 17..20 -> "Enerjik"
        else -> "Sakin"
    }

    fun pick(
        stations: List<Station>,
        favorites: Set<String>,
        recent: List<String>,
        broken: Set<String>,
        hour: Int,
        mood: String? = null,
        exclude: String? = null,
        random: Random = Random.Default,
    ): Pick? {
        val effectiveMood = mood ?: moodForHour(hour)
        val recentSet = recent.take(4).toSet()
        val candidates = stations.filter { it.resolvedUrl !in broken && it.resolvedUrl != exclude }
            .let { list -> list.filter { it.resolvedUrl !in recentSet }.ifEmpty { list } }
        if (candidates.isEmpty()) return null

        val favGenres = stations.filter { it.resolvedUrl in favorites }.groupingBy { it.genre.lowercase() }.eachCount()
        val scored = candidates.map { st ->
            val moodScore = RadioMoodMatcher.score(st, effectiveMood).coerceAtMost(6)
            val genreScore = (favGenres[st.genre.lowercase()] ?: 0).coerceAtMost(3) * 2
            val favBonus = if (st.resolvedUrl in favorites) 2 else 0
            val logoBonus = if (st.logoUrl.isNotBlank()) 1 else 0
            st to (moodScore * 2 + genreScore + favBonus + logoBonus + random.nextDouble() * 4)
        }.sortedByDescending { it.second }

        // choose among the best few so "başka bir tane" keeps feeling fresh
        val top = scored.take(6)
        val (station, _) = top[random.nextInt(top.size)]
        val reason = when {
            mood != null && RadioMoodMatcher.score(station, mood) > 0 -> "\"$mood\" moduna uygun bir frekans."
            station.resolvedUrl in favorites -> "Favorilerinden, şu an tam yerine oturur."
            (favGenres[station.genre.lowercase()] ?: 0) > 0 -> "Sevdiğin ${station.genre} tarzına yakın, yeni bir keşif."
            mood == null && RadioMoodMatcher.score(station, effectiveMood) > 0 -> timeReason(hour)
            else -> "Uzun zamandır açmadığın bir frekans."
        }
        return Pick(station, reason, effectiveMood)
    }

    private fun timeReason(hour: Int) = when (hour) {
        in 0..5 -> "Gecenin bu saatine sakin bir eşlik."
        in 6..9 -> "Güne enerjik bir başlangıç."
        in 10..16 -> "Gün ortası için akıp giden bir yayın."
        in 17..20 -> "Akşama doğru biraz hareket."
        else -> "Akşamın keyfine yakışır bir frekans."
    }
}
