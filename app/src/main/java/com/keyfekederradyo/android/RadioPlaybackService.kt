package com.keyfekederradyo.android

import android.app.PendingIntent
import android.content.Intent
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

@UnstableApi
class RadioPlaybackService : MediaSessionService() {
    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private val timerHandler = Handler(Looper.getMainLooper())
    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectAttempt = 0
    private val reconnectDelays = longArrayOf(4_000L, 8_000L, 15_000L, 30_000L)
    private val reconnectRunnable = object : Runnable {
        override fun run() {
            if (!::player.isInitialized) return
            val item = player.currentMediaItem ?: return
            if (!player.playWhenReady) return
            val until = getSharedPreferences("radio", MODE_PRIVATE).getLong("sleep_until", 0L)
            if (until > 0L && until <= System.currentTimeMillis()) return
            player.setMediaItem(item, player.currentPosition.coerceAtLeast(0L))
            player.prepare()
            player.play()
        }
    }
    private val timerRunnable = object : Runnable {
        override fun run() {
            val until = getSharedPreferences("radio", MODE_PRIVATE).getLong("sleep_until", 0L)
            if (until <= 0L) return
            val remaining = until - System.currentTimeMillis()
            if (remaining <= 0L) {
                reconnectHandler.removeCallbacks(reconnectRunnable)
                player.pause()
                player.clearMediaItems()
                PlaybackState.setPlaying(null)
                getSharedPreferences("radio", MODE_PRIVATE).edit().remove("sleep_until").apply()
                stopSelf()
            } else {
                timerHandler.postDelayed(this, minOf(remaining, 30_000L))
            }
        }
    }

    // The sleep timer is set from the UI while the service is already running: re-arm on every change
    private val prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "sleep_until") {
            timerHandler.removeCallbacks(timerRunnable)
            timerHandler.post(timerRunnable)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSharedPreferences("radio", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(prefsListener)
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (player.isPlaying) {
                    reconnectAttempt = 0
                    PlaybackState.setPlaying(mediaItem?.mediaId)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) reconnectAttempt = 0
                PlaybackState.setPlaying(if (isPlaying) player.currentMediaItem?.mediaId else null)
            }

            override fun onMetadata(metadata: Metadata) {
                for (i in 0 until metadata.length()) {
                    val info = metadata.get(i) as? IcyInfo ?: continue
                    val raw = info.title?.trim().orEmpty()
                    if (raw.isBlank()) continue
                    val current = player.currentMediaItem ?: continue
                    val parts = raw.split(Regex("\\s+[-–—|/]\\s+"), limit = 2)
                    val artist: String?
                    val song: String
                    if (parts.size == 2) {
                        artist = parts[0].trim().takeIf { it.isNotBlank() }
                        song = parts[1].trim()
                    } else {
                        artist = current.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() && it != "Keyfe Keder Radyo" }
                        song = raw
                    }
                    if (song.isBlank()) continue
                    val updated = MediaMetadata.Builder()
                        .setTitle(song)
                        .setArtist(artist ?: "Keyfe Keder Radyo")
                        .setAlbumTitle(current.mediaMetadata.albumTitle ?: "Canlı Yayın")
                        .setArtworkUri(current.mediaMetadata.artworkUri)
                        .build()
                    val updatedItem = current.buildUpon().setMediaMetadata(updated).build()
                    val index = player.currentMediaItemIndex
                    if (index >= 0) player.replaceMediaItem(index, updatedItem)
                    break
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                PlaybackState.setPlaying(null)
                reconnectHandler.removeCallbacks(reconnectRunnable)
                if (player.currentMediaItem != null && player.playWhenReady) {
                    val delay = reconnectDelays[reconnectAttempt.coerceAtMost(reconnectDelays.lastIndex)]
                    reconnectAttempt = (reconnectAttempt + 1).coerceAtMost(reconnectDelays.lastIndex)
                    reconnectHandler.postDelayed(reconnectRunnable, delay)
                }
            }
        })

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val sessionActivity = PendingIntent.getActivity(
            this,
            1001,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity)
            .build()
        timerHandler.post(timerRunnable)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!player.isPlaying && !player.playWhenReady) stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        getSharedPreferences("radio", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(prefsListener)
        timerHandler.removeCallbacks(timerRunnable)
        reconnectHandler.removeCallbacks(reconnectRunnable)
        PlaybackState.setPlaying(null)
        if (::mediaSession.isInitialized) mediaSession.release()
        if (::player.isInitialized) player.release()
        super.onDestroy()
    }
}
