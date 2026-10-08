package com.keyfekederradyo.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

/** Station <-> MediaItem mapping shared by the app UI, the playback service and Android Auto. */
object StationMedia {
    const val EXTRA_GENRE = "genre"
    const val EXTRA_LOGO = "logo"
    const val EXTRA_HAS_TRACK = "has_track"
    const val LIVE = "CANLI"

    /** Branded neon cover, rendered by [CoverArtBitmapLoader] for the notification, lock screen and Auto. */
    @Suppress("UNUSED_PARAMETER")
    fun artworkUri(context: Context, station: Station): Uri = CoverArt.uri(station)

    /** Metadata shown while no song title is known: the station name, marked live. */
    fun stationMetadata(context: Context, station: Station): MediaMetadata = MediaMetadata.Builder()
        .setTitle(station.name)
        .setDisplayTitle(station.name)
        .setArtist(LIVE)
        .setStation(station.name)
        .setAlbumTitle(station.genre.ifBlank { "Canlı Yayın" })
        .setGenre(station.genre)
        .setArtworkUri(artworkUri(context, station))
        .setIsPlayable(true)
        .setIsBrowsable(false)
        .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
        .setExtras(Bundle().apply {
            putString(EXTRA_GENRE, station.genre)
            putString(EXTRA_LOGO, station.logoUrl)
            putBoolean(EXTRA_HAS_TRACK, false)
        })
        .build()

    fun toMediaItem(context: Context, station: Station): MediaItem = MediaItem.Builder()
        .setMediaId(station.resolvedUrl)
        .setUri(station.resolvedUrl)
        .setMediaMetadata(stationMetadata(context, station))
        .build()

    /** Metadata with a live song: title = song, artist = singer (or "CANLI"), station kept. */
    fun trackMetadata(base: MediaMetadata, song: String, artist: String?): MediaMetadata = base.buildUpon()
        .setTitle(song)
        .setDisplayTitle(song)
        .setArtist(artist?.takeIf { it.isNotBlank() } ?: base.station ?: LIVE)
        .setExtras(Bundle(base.extras ?: Bundle()).apply { putBoolean(EXTRA_HAS_TRACK, true) })
        .build()
}

/** Parses "Artist - Song" style radio titles; returns null for slogans/station names. */
object TrackParser {
    private val junk = Regex("(?i)^(\\s*|-|unknown|bilinmiyor|reklam|advert|jingle|canl[ıi] yay[ıi]n|live|on air|www\\..*|http.*)$")

    fun parse(raw: String?, stationName: String): Pair<String?, String>? {
        val cleaned = raw?.replace(Regex("\\s+"), " ")?.trim()?.trim('-', '|', ' ') ?: return null
        if (cleaned.isBlank() || junk.matches(cleaned)) return null
        if (cleaned.equals(stationName, true) || stationName.contains(cleaned, true)) return null
        val parts = cleaned.split(Regex("\\s+[-–—|]\\s+|\\s+/\\s+"), limit = 2)
        return if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
            val artist = parts[0].trim(); val song = parts[1].trim()
            if (song.equals(stationName, true)) null else artist to song
        } else null to cleaned
    }
}
