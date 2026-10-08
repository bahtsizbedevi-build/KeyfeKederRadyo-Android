package com.keyfekederradyo.android

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView

/** What the listener told us on first launch. */
data class Profile(val name: String, val genres: Set<String>, val city: String) {
    companion object {
        fun load(context: Context): Profile {
            val p = context.getSharedPreferences("radio", Context.MODE_PRIVATE)
            return Profile(p.getString("user_name", "").orEmpty(),
                p.getString("fav_genres", "").orEmpty().split("|").filter { it.isNotBlank() }.toSet(),
                p.getString("home_city", "").orEmpty())
        }

        fun save(context: Context, profile: Profile) {
            context.getSharedPreferences("radio", Context.MODE_PRIVATE).edit()
                .putString("user_name", profile.name.trim().take(24))
                .putString("fav_genres", profile.genres.joinToString("|"))
                .putString("home_city", profile.city)
                .putBoolean("onboarded", true).apply()
        }

        fun onboarded(context: Context) = context.getSharedPreferences("radio", Context.MODE_PRIVATE).getBoolean("onboarded", false)
    }
}

/**
 * First-launch welcome in three short steps: name, favourite genres, city.
 * Every step can be skipped; the home screen is personalised from the answers.
 */
class OnboardingView(
    context: Context,
    private val genres: List<String>,
    private val cities: List<String>,
    initial: Profile,
    private val onDone: (Profile) -> Unit,
) : FrameLayout(context) {
    private val ui = Ui(context)
    private var step = 0
    private var name = initial.name
    private val pickedGenres = initial.genres.toMutableSet()
    private var city = initial.city
    private val body = FrameLayout(context)
    private val dots = LinearLayout(context).apply { gravity = Gravity.CENTER }
    private val next = ui.text("", 15f, Neon.TEXT, true).apply { gravity = Gravity.CENTER }
    private val back = ui.text("Geri", 14f, Neon.MUTED, true).apply { gravity = Gravity.CENTER; setPadding(ui.dp(20), 0, ui.dp(20), 0) }
    private var nameField: EditText? = null
    private var shownStep = -1

    init {
        isClickable = true
        setBackgroundColor(Neon.BG)
        addView(AmbientBackgroundView(context), LayoutParams(-1, -1))
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(24), ui.dp(24), ui.dp(24), ui.dp(24)) }
        addView(column, LayoutParams(-1, -1))
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(dots, LinearLayout.LayoutParams(0, ui.dp(40), 1f))
        top.addView(ui.text("Atla", 14f, Neon.MUTED, true).apply {
            setPadding(ui.dp(14), ui.dp(8), ui.dp(4), ui.dp(8)); isClickable = true
            setOnClickListener { finish() }
        })
        column.addView(top)
        column.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        val bottom = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        back.setOnClickListener { if (step > 0) { step--; render() } }
        bottom.addView(back, LinearLayout.LayoutParams(-2, ui.dp(56)))
        next.background = GlassDrawable(context, 28f, Neon.ORANGE, 0x55FFFFFF, 0, Neon.BRAND)
        next.isClickable = true
        next.setOnClickListener { Ui.pop(it); if (step < 2) { readName(); step++; render() } else finish() }
        bottom.addView(next, LinearLayout.LayoutParams(0, ui.dp(56), 1f))
        column.addView(bottom)
        render()
    }

    private fun readName() { nameField?.let { name = it.text.toString().trim() } }

    private fun render() {
        readName()
        body.removeAllViews()
        dots.removeAllViews()
        repeat(3) { i ->
            dots.addView(View(context).apply { background = ui.glass(4f, fill = if (i == step) Neon.ORANGE else 0x33FFFFFF) },
                LinearLayout.LayoutParams(if (i == step) ui.dp(26) else ui.dp(8), ui.dp(8)).apply { rightMargin = ui.dp(6) })
        }
        back.visibility = if (step == 0) View.INVISIBLE else View.VISIBLE
        next.text = if (step < 2) "Devam" else "Haydi başlayalım"
        val page = when (step) { 0 -> welcome(); 1 -> genrePage(); else -> cityPage() }
        body.addView(page, LayoutParams(-1, -1))
        if (shownStep != step) { // slide in only when the step changes, not on every chip tap
            page.alpha = 0f; page.translationX = ui.dpf(30f)
            page.animate().alpha(1f).translationX(0f).setDuration(320).setInterpolator(DecelerateInterpolator()).start()
            shownStep = step
        }
    }

    private fun welcome(): View {
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        c.addView(ImageView(context).apply { setImageResource(R.drawable.keyfe_keder_brand) },
            LinearLayout.LayoutParams(ui.dp(190), ui.dp(190)).apply { topMargin = ui.dp(24); bottomMargin = ui.dp(18) })
        c.addView(ui.text("Hoş geldin!", 30f, Neon.TEXT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
        c.addView(ui.text("Keyfe Keder senin radyon. 560'tan fazla canlı yayın, ruh haline göre seçimler ve biraz da sohbet.", 14.5f, Neon.MUTED, lines = 3).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(10) })
        c.addView(ui.text("Sana nasıl hitap edelim?", 16f, Neon.TEXT, true).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(28) })
        val field = EditText(context).apply {
            hint = "Adın (isteğe bağlı)"; setHintTextColor(Neon.MUTED); setTextColor(Neon.TEXT); textSize = 17f
            setSingleLine(true); inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            gravity = Gravity.CENTER; background = ui.glass(20f); setText(name)
        }
        nameField = field
        c.addView(field, LinearLayout.LayoutParams(-1, ui.dp(56)).apply { topMargin = ui.dp(12) })
        return ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(c) }
    }

    private fun genrePage(): View {
        nameField = null
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val hello = if (name.isNotBlank()) "Tanıştığımıza sevindim, $name!" else "Tanıştığımıza sevindim!"
        c.addView(ui.text(hello, 26f, Neon.TEXT, true, lines = 2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        c.addView(ui.text("Neler dinlemeyi seversin? Birkaç tane seç, ana sayfanı ona göre hazırlayalım.", 14.5f, Neon.MUTED, lines = 3),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(18) })
        c.addView(flow(genres, { it in pickedGenres }) { g -> if (!pickedGenres.add(g)) pickedGenres.remove(g); render() })
        return ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(c) }
    }

    private fun cityPage(): View {
        val c = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        c.addView(ui.text("Nerelisin?", 26f, Neon.TEXT, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(20) })
        c.addView(ui.text("Şehrinin yerel radyolarını öne çıkaralım. İstersen \"Fark etmez\" de.", 14.5f, Neon.MUTED, lines = 3),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8); bottomMargin = ui.dp(18) })
        c.addView(flow(listOf("Fark etmez") + cities, { it == city || (it == "Fark etmez" && city.isBlank()) }) { pick ->
            city = if (pick == "Fark etmez") "" else pick; render()
        })
        return ScrollView(context).apply { isVerticalScrollBarEnabled = false; addView(c) }
    }

    /** Wrapping rows of chips. */
    private fun flow(items: List<String>, selected: (String) -> Boolean, onTap: (String) -> Unit): View {
        val box = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val maxWidth = resources.displayMetrics.widthPixels - ui.dp(48)
        var row = LinearLayout(context); var used = 0
        items.forEach { item ->
            val chip = ui.chip(item, if (selected(item)) R.drawable.ic_check else null, selected(item)) { onTap(item) }
            val w = (item.length * ui.dpf(8.5f)).toInt() + ui.dp(if (selected(item)) 64 else 40)
            if (used + w > maxWidth && row.childCount > 0) { box.addView(row); row = LinearLayout(context); used = 0 }
            row.addView(chip, LinearLayout.LayoutParams(-2, ui.dp(44)).apply { setMargins(0, 0, ui.dp(8), ui.dp(10)) })
            used += w + ui.dp(8)
        }
        if (row.childCount > 0) box.addView(row)
        return box
    }

    private fun finish() {
        readName()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        animate().alpha(0f).setDuration(260).withEndAction {
            (parent as? ViewGroup)?.removeView(this)
            onDone(Profile(name, pickedGenres, city))
        }.start()
    }
}
