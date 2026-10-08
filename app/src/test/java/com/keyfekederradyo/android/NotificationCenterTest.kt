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
        val comebackTitles = config.comeback.map { it.title }.filter { "{name}" !in it }.toSet()
        repeat(20) { seed ->
            val msg = NotificationCenter.compose(config, "weekday_evening", "Kral FM", 4, null, 20, Random(seed))
            assertNotNull(msg)
            assertTrue(msg!!.first, msg.first in comebackTitles)
        }
    }

    @Test
    fun random_slot_is_stable_and_inside_its_window() {
        // afternoon_surprise: 13:00-16:30; on days it fires, the time must not move between calls
        var fired = 0
        for (day in 1..28) {
            val start = at(2026, 11, day, 12, 0)
            val a = NotificationCenter.next(config, start)!!
            val b = NotificationCenter.next(config, start)!!
            assertEquals(a, b)
            if (a.second == "afternoon_surprise") {
                fired++
                val c = Calendar.getInstance().apply { timeInMillis = a.first }
                val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
                assertTrue("$minutes", minutes in 13 * 60 until 16 * 60 + 30)
            }
        }
        assertTrue("surprise should fire on some days, fired $fired", fired in 3..25)
    }

    @Test
    fun name_templates_only_when_a_name_is_known() {
        repeat(40) { seed ->
            val (t, b) = NotificationCenter.compose(config, "weekday_morning", "Kral FM", 0, null, 8, Random(seed))!!
            assertFalse("{name}" in t + b)
        }
        val named = (0 until 60).mapNotNull { NotificationCenter.compose(config, "weekday_morning", "Kral FM", 0, null, 8, Random(it), "Ayşe") }
        assertTrue(named.any { "Ayşe" in it.first })
    }
}
