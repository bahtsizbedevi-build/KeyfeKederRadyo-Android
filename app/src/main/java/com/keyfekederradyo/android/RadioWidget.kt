package com.keyfekederradyo.android

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.view.KeyEvent
import android.widget.RemoteViews

/**
 * Home-screen widget: logo, station, song and prev / play-pause / next.
 * Buttons send media-button events to the session (works even when the app is closed:
 * the service resumes the last station). The service pushes updates via [update].
 */
class RadioWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val prefs = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
        val last = prefs.getString("widget_title", null)
        render(context, last ?: "Bir radyo seç", prefs.getString("widget_subtitle", null) ?: "Dokun, keyfine göre çalsın", false, null)
    }

    companion object {
        /** Called by the playback service whenever the station, song or play state changes. */
        fun update(context: Context, title: String, subtitle: String, playing: Boolean, logo: Bitmap?) {
            context.getSharedPreferences("radio", Context.MODE_PRIVATE).edit()
                .putString("widget_title", title).putString("widget_subtitle", subtitle).apply()
            render(context, title, subtitle, playing, logo)
        }

        private fun render(context: Context, title: String, subtitle: String, playing: Boolean, logo: Bitmap?) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RadioWidget::class.java))
            if (ids.isEmpty()) return
            val views = RemoteViews(context.packageName, R.layout.widget_radio).apply {
                setTextViewText(R.id.widget_title, title)
                setTextViewText(R.id.widget_subtitle, subtitle)
                setTextViewText(R.id.widget_live, if (playing) "● CANLI" else "KEYFE KEDER")
                setImageViewResource(R.id.widget_play, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
                if (logo != null) setImageViewBitmap(R.id.widget_logo, logo) else setImageViewResource(R.id.widget_logo, R.drawable.keyfe_keder_brand)
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(context, 0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                setOnClickPendingIntent(R.id.widget_play, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
                setOnClickPendingIntent(R.id.widget_next, mediaButton(context, KeyEvent.KEYCODE_MEDIA_NEXT))
                setOnClickPendingIntent(R.id.widget_prev, mediaButton(context, KeyEvent.KEYCODE_MEDIA_PREVIOUS))
            }
            manager.updateAppWidget(ids, views)
        }

        private fun mediaButton(context: Context, keyCode: Int): PendingIntent {
            val intent = Intent(Intent.ACTION_MEDIA_BUTTON)
                .setComponent(ComponentName(context, androidx.media3.session.MediaButtonReceiver::class.java))
                .putExtra(Intent.EXTRA_KEY_EVENT, KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            return PendingIntent.getBroadcast(context, keyCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
