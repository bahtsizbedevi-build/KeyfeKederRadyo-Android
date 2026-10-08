package com.keyfekederradyo.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout

/** Which output the user picked inside the app (null = let Android decide). Shared with the service. */
object AudioOutput {
    @Volatile var preferredId: Int? = null

    private val mediaTypes = setOf(
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL,
    ) + (if (Build.VERSION.SDK_INT >= 31) setOf(AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER) else emptySet())

    /** Connected outputs that can play music, one entry per device. */
    fun outputs(context: Context): List<AudioDeviceInfo> =
        context.getSystemService(AudioManager::class.java).getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.type in mediaTypes }
            .distinctBy { it.type to it.productName.toString() }

    /** The output music is going to right now. */
    fun current(context: Context): AudioDeviceInfo? {
        val all = outputs(context)
        preferredId?.let { id -> all.firstOrNull { it.id == id }?.let { return it } }
        if (Build.VERSION.SDK_INT >= 33) {
            val am = context.getSystemService(AudioManager::class.java)
            val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
            am.getAudioDevicesForAttributes(attrs).firstOrNull()?.let { routed -> all.firstOrNull { it.id == routed.id }?.let { return it } }
        }
        val priority = listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)
        return priority.firstNotNullOfOrNull { t -> all.firstOrNull { it.type == t } } ?: all.firstOrNull()
    }

    fun name(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Telefon hoparlörü"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Kablolu kulaklık"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        else -> d.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: "Bluetooth cihazı"
    }

    fun kind(d: AudioDeviceInfo): String = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Dahili"
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Kablolu"
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB"
        AudioDeviceInfo.TYPE_HDMI -> "Ekran"
        else -> "Bluetooth"
    }

    fun icon(d: AudioDeviceInfo?): Int = when (d?.type) {
        null, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> R.drawable.ic_speaker
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> R.drawable.ic_headphones
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> R.drawable.ic_usb
        AudioDeviceInfo.TYPE_HDMI -> R.drawable.ic_television
        else -> R.drawable.ic_bluetooth
    }

    fun bluetoothOn(context: Context): Boolean? = runCatching {
        context.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.isEnabled
    }.getOrNull()

    /** Android's own "play on…" picker (pairs/connects Bluetooth devices); falls back to Bluetooth settings. */
    fun openSystemPicker(context: Context) {
        if (Build.VERSION.SDK_INT >= 34 && runCatching { android.media.MediaRouter2.getInstance(context).showSystemOutputSwitcher() }.getOrDefault(false)) return
        val dialog = Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
            .setPackage("com.android.systemui").putExtra("package_name", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.sendBroadcast(dialog); true }.getOrDefault(false) && Build.VERSION.SDK_INT in 30..33) return
        openBluetoothSettings(context)
    }

    fun openBluetoothSettings(context: Context) {
        try { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: ActivityNotFoundException) { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * "Ses çıkışı" bottom sheet: the active output, every connected output (tap to switch),
 * and shortcuts to connect a Bluetooth device. Updates live as devices come and go.
 */
class AudioOutputSheet(
    context: Context,
    private val onSelect: (AudioDeviceInfo?) -> Unit,
    private val onClose: () -> Unit,
) : FrameLayout(context) {
    private val ui = Ui(context)
    private val sheet = LinearLayout(context)
    private val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val audio = context.getSystemService(AudioManager::class.java)
    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = render()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (removed.any { it.id == AudioOutput.preferredId }) { AudioOutput.preferredId = null; onSelect(null) }
            render()
        }
    }

    init {
        isClickable = true
        setBackgroundColor(0xB3000000.toInt())
        setOnClickListener { close() }
        sheet.apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(22))
            background = GlassDrawable(context, 32f, 0xF2121019.toInt(), Neon.STROKE)
            isClickable = true
        }
        sheet.addView(View(context).apply { background = ui.glass(3f, fill = 0x55FFFFFF) },
            LinearLayout.LayoutParams(ui.dp(44), ui.dp(5)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = ui.dp(16) })
        sheet.addView(ui.label("Ses çıkışı").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        sheet.addView(ui.text("Müziğin nereden çalacağını seç", 13f, Neon.MUTED).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(16) })
        sheet.addView(content)
        addView(sheet, LayoutParams(-1, -2, Gravity.BOTTOM))
        render()
    }

    private fun render() {
        content.removeAllViews()
        val current = AudioOutput.current(context)

        // hero: what is playing right now
        val hero = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            background = ui.glass(24f, Neon.withAlpha(Neon.ORANGE, 90), gradient = intArrayOf(0x59FF7A1A, 0x40FF2E88, 0x1A8B5CFF))
        }
        hero.addView(FrameLayout(context).apply {
            background = GlassDrawable(context, 24f, Neon.ORANGE, 0x55FFFFFF, 0, Neon.BRAND)
            addView(ui.icon(AudioOutput.icon(current), Neon.TEXT, 26), LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
        }, LinearLayout.LayoutParams(ui.dp(54), ui.dp(54)).apply { rightMargin = ui.dp(14) })
        val texts = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.label("Şu an çalıyor", Neon.TEXT))
        texts.addView(ui.text(current?.let { AudioOutput.name(it) } ?: "Telefon hoparlörü", 18f, Neon.TEXT, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        hero.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        content.addView(hero, LinearLayout.LayoutParams(-1, -2))

        // every connected output
        val outputs = AudioOutput.outputs(context)
        if (outputs.size > 1) {
            content.addView(ui.label("Bağlı cihazlar", Neon.MUTED).apply { setPadding(ui.dp(4), ui.dp(18), 0, ui.dp(8)) })
            outputs.forEach { d ->
                val active = d.id == current?.id
                val row = LinearLayout(context).apply {
                    gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); isClickable = true
                    background = if (active) ui.glass(18f, gradient = intArrayOf(0x33FF7A1A, 0x1FFF2E88)) else ui.glass(18f)
                    setOnClickListener {
                        Ui.pop(this)
                        // speaker = let Android route normally again; anything else = route there
                        val choice = if (d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && outputs.none { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES }) null else d
                        AudioOutput.preferredId = choice?.id
                        onSelect(choice)
                        postDelayed({ render() }, 250)
                    }
                }
                row.addView(ui.icon(AudioOutput.icon(d), if (active) Neon.ORANGE else Neon.TEXT, 22), LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)).apply { rightMargin = ui.dp(14) })
                val t = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                t.addView(ui.text(AudioOutput.name(d), 15f, Neon.TEXT, true))
                t.addView(ui.text(AudioOutput.kind(d), 12f, Neon.MUTED))
                row.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
                if (active) row.addView(ui.icon(R.drawable.ic_check, Neon.ORANGE, 20))
                content.addView(row, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(8) })
            }
        }

        val bt = AudioOutput.bluetoothOn(context)
        if (bt == false) {
            content.addView(ui.text("Bluetooth kapalı. Kulaklık ya da hoparlör bağlamak için aç.", 13f, Neon.MUTED, lines = 2).apply {
                gravity = Gravity.CENTER; setPadding(ui.dp(8), ui.dp(16), ui.dp(8), 0)
            })
        }

        // actions
        val primary = LinearLayout(context).apply {
            gravity = Gravity.CENTER; isClickable = true
            background = GlassDrawable(context, 26f, Neon.ORANGE, 0x55FFFFFF, 0, Neon.BRAND)
            setOnClickListener { Ui.pop(this); if (bt == false) AudioOutput.openBluetoothSettings(context) else AudioOutput.openSystemPicker(context) }
        }
        primary.addView(ui.icon(R.drawable.ic_bluetooth, Neon.TEXT, 20).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(10) })
        primary.addView(ui.text(if (bt == false) "Bluetooth'u aç" else "Bluetooth cihazı bağla", 15f, Neon.TEXT, true))
        content.addView(primary, LinearLayout.LayoutParams(-1, ui.dp(54)).apply { topMargin = ui.dp(18) })

        val secondary = LinearLayout(context).apply {
            gravity = Gravity.CENTER; isClickable = true
            setOnClickListener { Ui.pop(this); AudioOutput.openBluetoothSettings(context) }
        }
        secondary.addView(ui.icon(R.drawable.ic_gear_small, Neon.MUTED, 18).apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(8) })
        secondary.addView(ui.text("Bluetooth ayarları", 13.5f, Neon.MUTED, true))
        content.addView(secondary, LinearLayout.LayoutParams(-1, ui.dp(46)).apply { topMargin = ui.dp(4) })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); audio.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper())) }
    override fun onDetachedFromWindow() { audio.unregisterAudioDeviceCallback(callback); super.onDetachedFromWindow() }

    fun show(parent: ViewGroup) {
        parent.addView(this, ViewGroup.LayoutParams(-1, -1))
        alpha = 0f; animate().alpha(1f).setDuration(200).start()
        sheet.translationY = ui.dpf(500f)
        sheet.animate().translationY(0f).setDuration(360).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    fun close() {
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(220).start()
        animate().alpha(0f).setDuration(220).withEndAction { (parent as? ViewGroup)?.removeView(this); onClose() }.start()
    }

    /** Re-reads devices (e.g. after returning from Bluetooth settings). */
    fun refresh() = render()
}
