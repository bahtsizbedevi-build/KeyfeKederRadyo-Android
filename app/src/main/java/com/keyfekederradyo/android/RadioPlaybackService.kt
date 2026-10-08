package com.keyfekederradyo.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Metadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture

@UnstableApi
class RadioPlaybackService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val prefs: SharedPreferences by lazy { getSharedPreferences("radio", MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stations: List<Station> = emptyList()
    private val byUrl get() = stations.associateBy { it.resolvedUrl }

    private var reconnectAttempt = 0
    private val reconnectDelays = longArrayOf(2_000L, 4_000L, 8_000L, 15_000L, 30_000L)
    private val reconnectRunnable = Runnable {
        if (!::player.isInitialized || player.currentMediaItem == null || !player.playWhenReady) return@Runnable
        if (sleepExpired()) return@Runnable
        player.prepare() // re-prepares the current item of the playlist; keeps next/previous intact
        player.play()
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            val until = prefs.getLong("sleep_until", 0L)
            if (until <= 0L) return
            val remaining = until - System.currentTimeMillis()
            if (remaining <= 0L) {
                handler.removeCallbacks(reconnectRunnable)
                player.pause()
                PlaybackState.setPlaying(null)
                prefs.edit().remove("sleep_until").apply()
            } else {
                handler.postDelayed(this, minOf(remaining, 30_000L))
            }
        }
    }

    // The sleep timer is set from the UI while the service is already running: re-arm on every change
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "sleep_until") {
            handler.removeCallbacks(timerRunnable)
            handler.post(timerRunnable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        stations = runCatching { StationRepository(this).loadLocal() }.getOrDefault(emptyList())
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        // Every decoded sample also goes to the spectrum analyser (UI visualiser)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(TeeAudioProcessor(SpectrumAnalyzer)))
                    .build()
        }
        player = ExoPlayer.Builder(this, renderers)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this, 1001, launchIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(sessionActivity)
            .build()
        handler.post(timerRunnable)
    }

    private var widgetLogoUrl = ""
    private var widgetLogo: android.graphics.Bitmap? = null

    /** Keeps the home-screen widget in sync with the station, song and play state. */
    private fun updateWidget() {
        val item = player.currentMediaItem
        val station = item?.let { byUrl[it.mediaId] }
        if (station == null) { RadioWidget.update(this, "Bir radyo seç", "Dokun, keyfine göre çalsın", false, null); return }
        val meta = player.mediaMetadata
        val hasTrack = meta.extras?.getBoolean(StationMedia.EXTRA_HAS_TRACK) == true
        val title = if (hasTrack) meta.title?.toString() ?: station.name else station.name
        val subtitle = when {
            hasTrack -> listOfNotNull(meta.artist?.toString()?.takeIf { it != StationMedia.LIVE }, station.name).joinToString(" • ")
            player.playbackState == Player.STATE_BUFFERING -> "Bağlanıyor…"
            player.isPlaying -> "Canlı yayın • ${station.genre.ifBlank { "Radyo" }}"
            else -> "Duraklatıldı"
        }
        val playing = player.isPlaying || player.playbackState == Player.STATE_BUFFERING && player.playWhenReady
        if (station.logoUrl != widgetLogoUrl) {
            widgetLogoUrl = station.logoUrl; widgetLogo = null
            if (station.logoUrl.isNotBlank()) StationImageLoader.load(station.logoUrl) { bmp ->
                if (widgetLogoUrl == station.logoUrl) { widgetLogo = bmp; updateWidget() }
            }
        }
        RadioWidget.update(this, title, subtitle, playing, widgetLogo)
    }

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                    Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED)) updateWidget()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            reconnectAttempt = 0
            mediaItem?.mediaId?.let { prefs.edit().putString("last_url", it).apply() }
            if (player.isPlaying) PlaybackState.setPlaying(mediaItem?.mediaId)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                reconnectAttempt = 0
                player.currentMediaItem?.mediaId?.let { prefs.edit().remove("bad_$it").apply() }
            }
            PlaybackState.setPlaying(if (isPlaying) player.currentMediaItem?.mediaId else null)
        }

        override fun onMetadata(metadata: Metadata) {
            var title: String? = null
            var artist: String? = null
            for (i in 0 until metadata.length()) {
                when (val entry = metadata.get(i)) {
                    is IcyInfo -> title = entry.title ?: title
                    is TextInformationFrame -> when (entry.id) {
                        "TIT2" -> title = entry.values.firstOrNull() ?: title
                        "TPE1" -> artist = entry.values.firstOrNull() ?: artist
                    }
                }
            }
            if (title == null && artist == null) return
            updateTrack(title, artist)
        }

        override fun onPlayerError(error: PlaybackException) {
            PlaybackState.setPlaying(null)
            // remembered so "Keyfime bırak" skips streams that are currently down
            player.currentMediaItem?.mediaId?.let { prefs.edit().putLong("bad_$it", System.currentTimeMillis()).apply() }
            handler.removeCallbacks(reconnectRunnable)
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                player.seekToDefaultPosition(); player.prepare(); return
            }
            if (player.currentMediaItem != null && player.playWhenReady) {
                val delay = reconnectDelays[reconnectAttempt.coerceAtMost(reconnectDelays.lastIndex)]
                reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(reconnectDelays.lastIndex)
                handler.postDelayed(reconnectRunnable, delay)
            }
        }
    }

    /** Applies a song from stream metadata; falls back to "CANLI • station" for slogans/ads. */
    private fun updateTrack(rawTitle: String?, rawArtist: String?) {
        val index = player.currentMediaItemIndex
        val current = player.currentMediaItem ?: return
        val station = byUrl[current.mediaId] ?: return
        val combined = if (rawArtist.isNullOrBlank()) rawTitle else "$rawArtist - ${rawTitle.orEmpty()}"
        val parsed = TrackParser.parse(combined, station.name)
        val newMeta = if (parsed == null) StationMedia.stationMetadata(this, station)
        else StationMedia.trackMetadata(StationMedia.stationMetadata(this, station), parsed.second, parsed.first)
        val old = current.mediaMetadata
        if (old.title == newMeta.title && old.artist == newMeta.artist) return
        if (index >= 0) player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(newMeta).build())
    }

    private fun sleepExpired(): Boolean {
        val until = prefs.getLong("sleep_until", 0L)
        return until > 0L && until <= System.currentTimeMillis()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying && !player.playWhenReady) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        handler.removeCallbacksAndMessages(null)
        PlaybackState.setPlaying(null)
        if (::session.isInitialized) session.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }

    // ---- Library: Android Auto / other media browsers, playlist expansion, resumption ----

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        private fun folder(id: String, title: String) = MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder().setTitle(title).setIsBrowsable(true).setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_RADIO_STATIONS).build()
            ).build()

        private fun children(parentId: String): List<MediaItem> {
            val all = stations
            return when {
                parentId == ROOT -> listOf(
                    folder(FAVORITES, "Favoriler"), folder(RECENT, "Son dinlenenler"),
                    folder(GENRES, "Türler"), folder(ALL, "Tüm radyolar")
                )
                parentId == FAVORITES -> all.filter { prefs.getBoolean(it.resolvedUrl, false) }.map { item(it) }
                parentId == RECENT -> prefs.getString("history", "").orEmpty().split("|")
                    .mapNotNull { url -> all.firstOrNull { it.resolvedUrl == url } }.map { item(it) }
                parentId == GENRES -> all.map { it.genre.ifBlank { "Radyo" } }.distinct().sorted()
                    .map { folder(GENRE_PREFIX + it, it) }
                parentId.startsWith(GENRE_PREFIX) -> all.filter { it.genre.ifBlank { "Radyo" } == parentId.removePrefix(GENRE_PREFIX) }.map { item(it) }
                parentId == ALL -> all.map { item(it) }
                else -> emptyList()
            }
        }

        private fun item(station: Station) = StationMedia.toMediaItem(this@RadioPlaybackService, station)

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, params: LibraryParams?):
            ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(folder(ROOT, "Keyfe Keder Radyo"), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, parentId: String, page: Int, pageSize: Int, params: LibraryParams?):
            ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val list = children(parentId)
            val from = (page * pageSize).coerceAtMost(list.size)
            val to = (from + pageSize).coerceAtMost(list.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list.subList(from, to)), params))
        }

        override fun onGetItem(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, mediaId: String):
            ListenableFuture<LibraryResult<MediaItem>> {
            val station = byUrl[mediaId] ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item(station), null))
        }

        override fun onSearch(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, params: LibraryParams?):
            ListenableFuture<LibraryResult<Void>> {
            session.notifySearchResultChanged(browser, query, search(query).size, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(session: MediaLibrarySession, browser: MediaSession.ControllerInfo, query: String, page: Int, pageSize: Int, params: LibraryParams?):
            ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val list = search(query).map { item(it) }
            val from = (page * pageSize).coerceAtMost(list.size)
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(list.subList(from, (from + pageSize).coerceAtMost(list.size))), params))
        }

        private fun search(query: String) = stations.filter {
            it.name.contains(query, true) || it.genre.contains(query, true)
        }

        /** Resolves station ids (from the app, Auto or a widget) to playable items with stream URIs. */
        override fun onAddMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>):
            ListenableFuture<MutableList<MediaItem>> = Futures.immediateFuture(mediaItems.map { resolve(it) }.toMutableList())

        /** Playing one station queues the whole list around it, so next/previous work everywhere. */
        override fun onSetMediaItems(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long):
            ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val all = stations
            if (mediaItems.size == 1) {
                val index = all.indexOfFirst { it.resolvedUrl == mediaItems[0].mediaId }
                if (index >= 0) {
                    return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(all.map { item(it) }, index, C.TIME_UNSET))
                }
            }
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(mediaItems.map { resolve(it) }, startIndex, startPositionMs))
        }

        /** Play button on a stopped notification / widget / Bluetooth: resume the last station. */
        override fun onPlaybackResumption(mediaSession: MediaSession, controller: MediaSession.ControllerInfo, isForPlayback: Boolean):
            ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val all = stations
            val last = prefs.getString("last_url", null)
            val index = all.indexOfFirst { it.resolvedUrl == last }.coerceAtLeast(0)
            if (all.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException("no stations"))
            return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(all.map { item(it) }, index, C.TIME_UNSET))
        }

        private fun resolve(requested: MediaItem): MediaItem {
            byUrl[requested.mediaId]?.let { return item(it) }
            if (requested.localConfiguration != null) return requested
            return requested.buildUpon().setUri(requested.mediaId).build()
        }
    }

    companion object {
        const val ROOT = "root"
        const val FAVORITES = "favorites"
        const val RECENT = "recent"
        const val GENRES = "genres"
        const val ALL = "all"
        const val GENRE_PREFIX = "genre:"
    }
}
