package com.keyfekederradyo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackParserTest {
    @Test
    fun splits_artist_and_song() {
        assertEquals("Sezen Aksu" to "Gidiyorum", TrackParser.parse("Sezen Aksu - Gidiyorum", "Kral FM"))
    }

    @Test
    fun keeps_title_without_artist() {
        assertEquals(null to "Gidiyorum", TrackParser.parse("Gidiyorum", "Kral FM"))
    }

    @Test
    fun ignores_station_name_and_slogans() {
        assertNull(TrackParser.parse("Kral FM", "Kral FM"))
        assertNull(TrackParser.parse("  ", "Kral FM"))
        assertNull(TrackParser.parse("Canlı Yayın", "Kral FM"))
        assertNull(TrackParser.parse("www.kralmuzik.com.tr", "Kral FM"))
    }
}
