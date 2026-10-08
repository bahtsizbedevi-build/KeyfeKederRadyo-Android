package com.keyfekederradyo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Calendar
import kotlin.random.Random

class NotificationCenterTest {
    private val config = NotificationCenter.parse(File("../data/notifications.json").readText())

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = Calendar.getInstance().apply {
        clear(); set(y, m - 1, d, h, min)
    }

    @Test
    fun bundled_templates_cover_every_slot() {
        config.slots.forEach { slot -> assertTrue(slot.id, config.templates.any { it.slot == slot.id }) }
    }

    @Test
    fun weekday_morning_comes_next_on_a_weekday_night() {
        // Wednesday 2026-10-07 23:00 -> Thursday 08:30
        val (time, id) = NotificationCenter.next(config, at(2026, 10, 7, 23, 0))!!
        assertEquals("weekday_morning", id)
        assertEquals(at(2026, 10, 8, 8, 30).timeInMillis, time)
    }

    @Test
    fun special_day_replaces_normal_slots() {
        // 2026-10-29 (Thursday): the special at 10:00 instead of 08:30
        val (_, id) = NotificationCenter.next(config, at(2026, 10, 29, 0, 5))!!
        assertEquals("special:2026-10-29", id)
    }

    @Test
    fun templates_needing_a_station_are_skipped_without_one() {
        repeat(30) { seed ->
            val (title, body) = NotificationCenter.compose(config, "weekday_morning", null, 0, null, 8, Random(seed))!!
            assertFalse(title + body, "{station}" in title + body)
        }
    }

    @Test
    fun station_name_is_filled_in() {
        val texts = (0 until 40).mapNotNull { NotificationCenter.compose(config, "weekday_evening", "Kral FM", 0, null, 20, Random(it)) }
        assertTrue(texts.any { "Kral FM" in it.second })
        assertTrue(texts.none { "{station}" in it.first + it.second })
    }

    @Test
    fun comeback_message_after_three_quiet_days() {
        assertNotNull(NotificationCenter.compose(config, "weekday_evening", "Kral FM", 4, null, 20).also {
            assertTrue(it!!.first in setOf("Seni özledik", "Bir süredir yoksun"))
        })
    }
}
