package com.keyfekederradyo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StationPickerTest {
    private fun st(name: String, genre: String) = Station(name = name, url = "https://x/$name", genre = genre)

    private val list = listOf(st("Lounge A", "Lounge"), st("Jazz B", "Jazz"), st("Pop C", "Pop"), st("Rock D", "Rock"))

    @Test
    fun never_picks_broken_or_excluded() {
        repeat(50) { seed ->
            val pick = StationPicker.pick(list, emptySet(), emptyList(), setOf("https://x/Lounge A"), 12,
                exclude = "https://x/Jazz B", random = Random(seed))!!
            assertNotEquals("Lounge A", pick.station.name)
            assertNotEquals("Jazz B", pick.station.name)
        }
    }

    @Test
    fun avoids_recently_played_when_possible() {
        repeat(50) { seed ->
            val pick = StationPicker.pick(list, emptySet(), listOf("https://x/Pop C", "https://x/Rock D"), emptySet(), 12, random = Random(seed))!!
            assertTrue(pick.station.name in setOf("Lounge A", "Jazz B"))
        }
    }

    @Test
    fun returns_null_when_everything_is_broken() {
        assertNull(StationPicker.pick(list, emptySet(), emptyList(), list.map { it.resolvedUrl }.toSet(), 12))
    }

    @Test
    fun night_hours_mean_calm_mood() {
        assertEquals("Kafamı dinliyorum", StationPicker.moodForHour(2))
        assertEquals("Sakin", StationPicker.moodForHour(23))
    }
}
