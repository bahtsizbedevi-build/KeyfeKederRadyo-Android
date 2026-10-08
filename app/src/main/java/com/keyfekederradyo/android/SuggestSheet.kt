package com.keyfekederradyo.android

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast

/** "Radyo öner": the listener names a station we don't have; it arrives as an e-mail to the developer. */
class SuggestSheet(context: Context, private val onClose: () -> Unit) : FrameLayout(context) {
    private val ui = Ui(context)
    private val sheet = LinearLayout(context)
    private val name = field("Radyonun adı", InputType.TYPE_TEXT_FLAG_CAP_WORDS)
    private val city = field("Şehri (varsa)", InputType.TYPE_TEXT_FLAG_CAP_WORDS)
    private val link = field("Web sitesi ya da yayın adresi (varsa)", InputType.TYPE_TEXT_VARIATION_URI)
    private val note = field("Eklemek istediğin bir not", InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)

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
        sheet.addView(ui.label("Radyo öner").apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        sheet.addView(ui.text("Sevdiğin radyo listede yok mu? Yaz, ekleyelim.", 13.5f, Neon.MUTED, lines = 2).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6); bottomMargin = ui.dp(16) })
        listOf(name, city, link, note).forEach { sheet.addView(it, LinearLayout.LayoutParams(-1, ui.dp(52)).apply { bottomMargin = ui.dp(10) }) }

        val send = LinearLayout(context).apply {
            gravity = Gravity.CENTER; isClickable = true
            background = GlassDrawable(context, 26f, Neon.ORANGE, 0x55FFFFFF, 0, Neon.BRAND)
            setOnClickListener { Ui.pop(this); send() }
        }
        send.addView(ui.text("Öneriyi gönder", 15f, Neon.TEXT, true))
        sheet.addView(send, LinearLayout.LayoutParams(-1, ui.dp(54)).apply { topMargin = ui.dp(8) })
        addView(sheet, LayoutParams(-1, -2, Gravity.BOTTOM))
    }

    private fun field(hint: String, flags: Int) = EditText(context).apply {
        this.hint = hint; setHintTextColor(Neon.MUTED); setTextColor(Neon.TEXT); textSize = 15f
        setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or flags
        background = ui.glass(16f); setPadding(ui.dp(16), 0, ui.dp(16), 0)
    }

    private fun send() {
        val station = name.text.toString().trim()
        if (station.isBlank()) { name.error = "Radyonun adını yaz"; name.requestFocus(); return }
        val body = buildString {
            appendLine("Radyo: $station")
            city.text.toString().trim().takeIf { it.isNotBlank() }?.let { appendLine("Şehir: $it") }
            link.text.toString().trim().takeIf { it.isNotBlank() }?.let { appendLine("Adres: $it") }
            note.text.toString().trim().takeIf { it.isNotBlank() }?.let { appendLine("Not: $it") }
            appendLine(); append("Keyfe Keder Radyo ${BuildConfig.VERSION_NAME}")
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(SUGGEST_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, "Radyo önerisi: $station")
            putExtra(Intent.EXTRA_TEXT, body)
        }
        try {
            context.startActivity(intent)
            Toast.makeText(context, "Teşekkürler! E-postanı gönderdiğinde öneri bize ulaşacak.", Toast.LENGTH_LONG).show()
            close()
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "E-posta uygulaması bulunamadı. Önerini $SUGGEST_EMAIL adresine yazabilirsin.", Toast.LENGTH_LONG).show()
        }
    }

    fun show(parent: ViewGroup) {
        parent.addView(this, ViewGroup.LayoutParams(-1, -1))
        alpha = 0f; animate().alpha(1f).setDuration(200).start()
        sheet.translationY = ui.dpf(600f)
        sheet.animate().translationY(0f).setDuration(360).setInterpolator(DecelerateInterpolator(2f)).start()
    }

    fun close() {
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        sheet.animate().translationY(sheet.height.toFloat()).setDuration(220).start()
        animate().alpha(0f).setDuration(220).withEndAction { (parent as? ViewGroup)?.removeView(this); onClose() }.start()
    }

    companion object {
        const val SUGGEST_EMAIL = "bahtsizbedevi.build@gmail.com"
    }
}
