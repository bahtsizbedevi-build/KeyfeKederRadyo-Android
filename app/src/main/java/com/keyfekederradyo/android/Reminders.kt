package com.keyfekederradyo.android

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.util.Calendar
import kotlin.random.Random

/**
 * Gentle daily reminders ("Günaydın, X seni bekliyor"): a morning and an evening slot,
 * each switchable in Ayarlar. Inexact alarms (no exact-alarm permission), skipped while
 * the radio is already playing, re-armed after a reboot or app update.
 */
object Reminders {
    const val CHANNEL = "reminders"
    const val EXTRA_PLAY_URL = "play_url"
    private const val EXTRA_SLOT = "slot"

    enum class Slot(val key: String, val hour: Int, val minute: Int, val label: String) {
        MORNING("rem_morning", 8, 30, "Sabah 08:30"),
        EVENING("rem_evening", 20, 30, "Akşam 20:30"),
    }

    fun isEnabled(context: Context, slot: Slot) = prefs(context).getBoolean(slot.key, true)

    fun setEnabled(context: Context, slot: Slot, on: Boolean) {
        prefs(context).edit().putBoolean(slot.key, on).apply()
        schedule(context)
    }

    /** (Re)arms every enabled slot for its next occurrence. */
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        Slot.entries.forEach { slot ->
            val pi = pendingIntent(context, slot)
            alarms.cancel(pi)
            if (!isEnabled(context, slot)) return@forEach
            val next = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, slot.hour); set(Calendar.MINUTE, slot.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                if (timeInMillis <= System.currentTimeMillis() + 60_000L) add(Calendar.DAY_OF_YEAR, 1)
            }
            // a ±15 min window lets the system batch it with other wake-ups (battery friendly)
            alarms.setWindow(AlarmManager.RTC_WAKEUP, next.timeInMillis - 15 * 60_000L, 30 * 60_000L, pi)
        }
    }

    private fun pendingIntent(context: Context, slot: Slot): PendingIntent = PendingIntent.getBroadcast(
        context, slot.ordinal + 300,
        Intent(context, ReminderReceiver::class.java).putExtra(EXTRA_SLOT, slot.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Shows a slot's reminder immediately (debug preview), ignoring the "already playing" rule. */
    fun preview(context: Context, slot: Slot) = notify(context, slot)

    internal fun show(context: Context, slotName: String?) {
        val slot = Slot.entries.firstOrNull { it.name == slotName } ?: return
        schedule(context) // tomorrow's
        if (PlaybackState.playingUrl != null) return // already listening, don't nag
        notify(context, slot)
    }

    private fun notify(context: Context, slot: Slot) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val stations = runCatching { StationRepository(context).loadLocal() }.getOrDefault(emptyList())
        val p = prefs(context)
        val favorites = stations.filter { p.getBoolean(it.resolvedUrl, false) }
        val recent = p.getString("history", "").orEmpty().split("|").firstOrNull { it.isNotBlank() }
        val station = favorites.randomOrNull() ?: stations.firstOrNull { it.resolvedUrl == recent }
        val (title, text) = message(slot, station?.name)

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Hatırlatmalar", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Sabah ve akşam radyo hatırlatmaları"
        })
        val open = PendingIntent.getActivity(context, 400, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.media3_notification_small_icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setColor(Neon.ORANGE)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (station != null) {
            val logo = runCatching { java.net.URL(station.logoUrl).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            builder.setLargeIcon(CoverArt.compose(context, station.name, logo, 256))
            val listen = PendingIntent.getActivity(context, 401, Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(EXTRA_PLAY_URL, station.resolvedUrl), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.addAction(R.drawable.ic_play, "${station.name} dinle", listen)
            builder.setContentIntent(listen)
        }
        manager.notify(500 + slot.ordinal, builder.build())
    }

    internal fun message(slot: Slot, station: String?, random: Random = Random.Default): Pair<String, String> {
        val s = station ?: "Keyfe Keder"
        val options = when (slot) {
            Slot.MORNING -> listOf(
                "Günaydın!" to "Güne müzikle başlamaya ne dersin? $s seni bekliyor.",
                "Kahven hazırsa frekans da hazır" to "Sabahın keyfini $s ile çıkar.",
                "Enerjini yükseltelim" to "Bir dokunuş yeter, $s canlı yayında.",
            )
            Slot.EVENING -> listOf(
                "Akşamın keyfi" to "Günün yorgunluğunu $s ile at.",
                "Bir frekans, bin keyif" to "Bu akşam ne dinlesek? $s ya da Keyfime Bırak, seçim senin.",
                "Kaldığın yerden devam" to "$s şu an canlı yayında.",
            )
        }
        return options[random.nextInt(options.size)]
    }

    private fun prefs(context: Context) = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
}

/** Fires a reminder, then re-arms the next one. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val slot = intent.getStringExtra("slot")
        Thread {
            try { Reminders.show(context.applicationContext, slot) } finally { pending.finish() }
        }.start()
    }
}

/** Alarms are cleared by a reboot or an app update: re-arm them. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Reminders.schedule(context.applicationContext)
        }
    }
}
