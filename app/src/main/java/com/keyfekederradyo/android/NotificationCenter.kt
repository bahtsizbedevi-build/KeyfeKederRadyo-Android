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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/**
 * Scheduled app notifications built from templates in data/notifications.json
 * (bundled in the APK, refreshed from GitHub, so messages and special days can change
 * without an app update). One alarm is armed at a time: the next slot or special.
 * Tapping a notification only opens the app; radio starts only from the explicit "Dinle" button.
 */
object NotificationCenter {
    const val CHANNEL = "updates"
    const val EXTRA_PLAY_URL = "play_url"
    private const val EXTRA_ID = "notify_id"
    private const val SOURCE =
        "https://raw.githubusercontent.com/bahtsizbedevi-build/KeyfeKederRadyo-Android/main/data/notifications.json"

    /** A fixed time, or (with [windowEnd]) a random time in [hour:minute, windowEnd) on a share ([chance]) of days. */
    data class Slot(val id: String, val days: Set<Int>, val hour: Int, val minute: Int, val windowEnd: Int? = null, val chance: Double = 1.0)
    data class Template(val slot: String, val title: String, val body: String)
    data class Special(val date: String, val hour: Int, val minute: Int, val title: String, val body: String)
    data class Config(val slots: List<Slot>, val templates: List<Template>, val comeback: List<Template>, val specials: List<Special>)

    // ---------------------------------------------------------------- config

    fun parse(json: String): Config {
        val o = JSONObject(json)
        fun time(s: String) = s.split(":").let { it[0].toInt() to it[1].toInt() }
        val slots = o.optJSONArray("slots")?.let { a ->
            List(a.length()) { i ->
                val s = a.getJSONObject(i)
                val days = s.getJSONArray("days").let { d -> List(d.length()) { d.getInt(it) }.toSet() }
                if (s.has("window")) {
                    val (from, to) = s.getString("window").split("-").map { time(it.trim()) }
                    Slot(s.getString("id"), days, from.first, from.second, to.first * 60 + to.second, s.optDouble("chance", 1.0))
                } else {
                    val (h, m) = time(s.getString("time"))
                    Slot(s.getString("id"), days, h, m)
                }
            }
        }.orEmpty()
        fun templates(key: String) = o.optJSONArray(key)?.let { a ->
            List(a.length()) { i -> a.getJSONObject(i).let { Template(it.optString("slot"), it.getString("title"), it.getString("body")) } }
        }.orEmpty()
        val specials = o.optJSONArray("specials")?.let { a ->
            List(a.length()) { i ->
                val s = a.getJSONObject(i)
                val (h, m) = time(s.getString("time"))
                Special(s.getString("date"), h, m, s.getString("title"), s.getString("body"))
            }
        }.orEmpty()
        return Config(slots, templates("templates"), templates("comeback"), specials)
    }

    private fun cacheFile(context: Context) = File(context.filesDir, "notifications.json")

    fun load(context: Context): Config {
        runCatching { parse(cacheFile(context).readText()) }.getOrNull()?.takeIf { it.slots.isNotEmpty() }?.let { return it }
        return parse(context.assets.open("notifications.json").bufferedReader().use { it.readText() })
    }

    /** Downloads the latest templates (background thread) and re-arms the schedule. */
    fun refresh(context: Context) {
        runCatching {
            val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(SOURCE).build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatching
                val body = r.body?.string().orEmpty()
                if (parse(body).slots.isNotEmpty()) cacheFile(context).writeText(body)
            }
        }
        schedule(context)
    }

    // ---------------------------------------------------------------- scheduling

    fun isEnabled(context: Context) = prefs(context).getBoolean("notify_enabled", true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean("notify_enabled", on).apply()
        schedule(context)
    }

    /** Next notification (time in millis to id), looking a week ahead. Specials replace that day's slots. */
    fun next(config: Config, now: Calendar): Pair<Long, String>? {
        val candidates = mutableListOf<Pair<Long, String>>()
        for (offset in 0..7) {
            val day = (now.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, offset) }
            val date = "%04d-%02d-%02d".format(day.get(Calendar.YEAR), day.get(Calendar.MONTH) + 1, day.get(Calendar.DAY_OF_MONTH))
            val special = config.specials.firstOrNull { it.date == date }
            if (special != null) {
                at(day, special.hour, special.minute)?.takeIf { it > now.timeInMillis }?.let { candidates += it to "special:$date" }
                continue
            }
            // Calendar: Sunday=1..Saturday=7  ->  1=Monday..7=Sunday
            val isoDay = (day.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
            config.slots.filter { isoDay in it.days }.forEach { slot ->
                val (h, m) = timeFor(slot, date) ?: return@forEach
                at(day, h, m)?.takeIf { it > now.timeInMillis }?.let { candidates += it to slot.id }
            }
            if (candidates.isNotEmpty()) break
        }
        return candidates.minByOrNull { it.first }
    }

    /** Fixed slots keep their time; random slots get a stable per-day time (or skip the day). */
    private fun timeFor(slot: Slot, date: String): Pair<Int, Int>? {
        val end = slot.windowEnd ?: return slot.hour to slot.minute
        val rnd = Random("$date/${slot.id}".hashCode())
        if (rnd.nextDouble() >= slot.chance) return null
        val start = slot.hour * 60 + slot.minute
        val minute = start + (rnd.nextInt(((end - start) / 5).coerceAtLeast(1))) * 5
        return minute / 60 to minute % 60
    }

    private fun at(day: Calendar, hour: Int, minute: Int): Long? = (day.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = PendingIntent.getBroadcast(context, 300, Intent(context, NotifyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarms.cancel(intent)
        if (!isEnabled(context)) return
        val config = runCatching { load(context) }.getOrNull() ?: return
        val (time, id) = next(config, Calendar.getInstance().apply { add(Calendar.MINUTE, 1) }) ?: return
        prefs(context).edit().putString("notify_next", id).apply()
        // ±10 min window: battery friendly, no exact-alarm permission needed
        alarms.setWindow(AlarmManager.RTC_WAKEUP, time - 10 * 60_000L, 20 * 60_000L,
            PendingIntent.getBroadcast(context, 300, Intent(context, NotifyReceiver::class.java).putExtra(EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    }

    // ---------------------------------------------------------------- content

    /** Picks the message for [id], fills {station}/{greeting}; never the same title twice in a row. */
    fun compose(config: Config, id: String, station: String?, daysAway: Long, lastTitle: String?, hour: Int,
                random: Random = Random.Default, name: String? = null): Pair<String, String>? {
        val special = if (id.startsWith("special:")) config.specials.firstOrNull { it.date == id.removePrefix("special:") } else null
        val pool = when {
            special != null -> listOf(Template(id, special.title, special.body))
            daysAway >= 3 && hour >= 17 && config.comeback.isNotEmpty() -> config.comeback
            else -> config.templates.filter { it.slot == id }
        }.filter { (station != null || "{station}" !in it.title + it.body) && (!name.isNullOrBlank() || "{name}" !in it.title + it.body) }
        val choice = pool.filter { it.title != lastTitle }.ifEmpty { pool }.randomOrNull(random) ?: return null
        val greeting = when (hour) { in 5..11 -> "Günaydın"; in 12..17 -> "İyi günler"; in 18..22 -> "İyi akşamlar"; else -> "İyi geceler" }
        fun fill(s: String) = s.replace("{station}", station.orEmpty()).replace("{greeting}", greeting).replace("{name}", name.orEmpty())
        return fill(choice.title) to fill(choice.body)
    }

    internal fun fire(context: Context, id: String?, preview: Boolean = false) {
        if (!preview) schedule(context) // arm the next one first
        if (id == null) return
        if (!preview && (!isEnabled(context) || PlaybackState.playingUrl != null)) return // already listening
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return

        val p = prefs(context)
        val stations = runCatching { StationRepository(context).loadLocal() }.getOrDefault(emptyList())
        val favorites = stations.filter { p.getBoolean(it.resolvedUrl, false) }
        val recent = p.getString("history", "").orEmpty().split("|").firstOrNull { it.isNotBlank() }
        val station = favorites.randomOrNull() ?: stations.firstOrNull { it.resolvedUrl == recent }
        val daysAway = (System.currentTimeMillis() - p.getLong("last_open", System.currentTimeMillis())) / 86_400_000L
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val config = runCatching { load(context) }.getOrNull() ?: return
        val (title, body) = compose(config, id, station?.name, daysAway, p.getString("notify_last", null), hour,
            name = p.getString("user_name", null)) ?: return
        p.edit().putString("notify_last", title).apply()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Keyfe Keder bildirimleri", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Günde en fazla iki kısa mesaj"
        })
        val open = PendingIntent.getActivity(context, 400, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.media3_notification_small_icon)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setColor(Neon.ORANGE)
            .setContentIntent(open) // opening the app never starts playback by itself
            .setAutoCancel(true)
        if (station != null) {
            val logo = runCatching { java.net.URL(station.logoUrl).openStream().use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
            builder.setLargeIcon(CoverArt.compose(context, station.name, logo, 256))
            if (body.contains(station.name) || title.contains(station.name)) {
                val listen = PendingIntent.getActivity(context, 401, Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_PLAY_URL, station.resolvedUrl), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                builder.addAction(R.drawable.ic_play, "Dinle", listen)
            }
        }
        manager.notify(500, builder.build())
    }

    /** Debug builds: show the next scheduled notification right now. */
    fun preview(context: Context) {
        val id = prefs(context).getString("notify_next", null)
            ?: runCatching { next(load(context), Calendar.getInstance())?.second }.getOrNull()
        fire(context, id, preview = true)
    }

    private fun prefs(context: Context) = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
}

/** Fires the scheduled notification, then arms the next one. */
class NotifyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val id = intent.getStringExtra("notify_id")
        Thread { try { NotificationCenter.fire(context.applicationContext, id) } finally { pending.finish() } }.start()
    }
}

/** Alarms are cleared by a reboot or an app update: re-arm. */
class NotifyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            NotificationCenter.schedule(context.applicationContext)
        }
    }
}
