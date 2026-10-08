package com.keyfekederradyo.android

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.Calendar
import java.util.concurrent.Executors
import kotlin.math.abs

class MainActivity : AppCompatActivity() {
    private val ui by lazy { Ui(this) }
    private val prefs by lazy { getSharedPreferences("radio", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var stations = emptyList<Station>()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playerError = false
    private var autoplayDone = false

    private enum class Page { HOME, RADIOS, DISCOVER, FAVORITES, SETTINGS, LIST }
    private var page = Page.HOME
    private var listTitle = ""
    private var listItems = emptyList<Station>()
    private var genreFilter: String? = null

    private lateinit var root: FrameLayout
    private lateinit var ambient: AmbientBackgroundView
    private lateinit var tagline: TextView
    private lateinit var search: EditText
    private lateinit var scroll: ScrollView
    private lateinit var scrollContent: LinearLayout
    private lateinit var listPage: LinearLayout
    private lateinit var listHeader: LinearLayout
    private lateinit var stationList: RecyclerView
    private lateinit var adapter: StationAdapter
    private lateinit var mini: FrameLayout
    private lateinit var dock: FrameLayout
    private lateinit var dockCard: LinearLayout
    private lateinit var miniArt: StationArtworkView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniSpectrum: AudioSpectrumView
    private lateinit var miniPlayIcon: ImageView
    private lateinit var miniPlay: FrameLayout
    private lateinit var navIndicator: View
    private lateinit var navRow: LinearLayout
    private val navItems = mutableListOf<Triple<View, ImageView, TextView>>()
    private val navIcons = listOf(R.drawable.ic_home to R.drawable.ic_home_fill, R.drawable.ic_radio to R.drawable.ic_radio_fill,
        R.drawable.ic_explore to R.drawable.ic_explore_fill, R.drawable.ic_heart_outline to R.drawable.ic_heart)
    private var fullPlayer: FullPlayerView? = null
    private var pickSheet: PickSheetView? = null
    private var outputSheet: AudioOutputSheet? = null
    private var pendingPlayUrl: String? = null
    private var systemBars = intArrayOf(0, 0, 0, 0)

    private val taglines = listOf("Bir frekans, bin keyif.", "Biraz müzik, biraz keyif.", "Keyfin ne isterse, frekans orada.", "Kafana göre bir radyo bulalım.")
    private var taglineIndex = 0

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Neon.BG))
        runCatching { android.net.http.HttpResponseCache.install(java.io.File(cacheDir, "http"), 16L * 1024 * 1024) }
        setContentView(buildUi())
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
        handler.postDelayed(taglineRunnable, 3500)
        handler.postDelayed(sleepTicker, 30_000)
        pendingPlayUrl = intent?.getStringExtra(Reminders.EXTRA_PLAY_URL)
        Reminders.schedule(this)
        connectPlayer()
        loadStations()
    }

    /** "X dinle" in a reminder notification. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(Reminders.EXTRA_PLAY_URL)?.let { pendingPlayUrl = it; playPending() }
    }

    override fun onResume() {
        super.onResume()
        // favourites may have changed from the notification heart; devices from Bluetooth settings
        if (::scrollContent.isInitialized && stations.isNotEmpty()) {
            if (page == Page.FAVORITES || page == Page.HOME) showPage(page, keepScroll = true) else if (page == Page.RADIOS || page == Page.LIST) adapter.refresh()
            refreshPlayerUi()
        }
        outputSheet?.refresh()
    }

    private fun playPending() {
        val url = pendingPlayUrl ?: return
        if (controller == null || stations.isEmpty()) return
        pendingPlayUrl = null
        autoplayDone = true
        stations.firstOrNull { it.resolvedUrl == url }?.let { play(it); openFullPlayer() }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        executor.shutdownNow()
        super.onDestroy()
    }

    private val taglineRunnable = object : Runnable {
        override fun run() {
            tagline.animate().alpha(0f).setDuration(200).withEndAction {
                taglineIndex = (taglineIndex + 1) % taglines.size
                tagline.text = taglines[taglineIndex]
                tagline.animate().alpha(1f).setDuration(300).start()
            }.start()
            handler.postDelayed(this, 4200)
        }
    }

    // keeps the "N dk" sleep labels current
    private val sleepTicker = object : Runnable {
        override fun run() {
            if (prefs.getLong("sleep_until", 0L) > 0L) { refreshPlayerUi(); if (page == Page.SETTINGS) showPage(Page.SETTINGS, keepScroll = true) }
            handler.postDelayed(this, 30_000)
        }
    }

    // ---------------------------------------------------------------- layout

    private fun buildUi(): View {
        root = FrameLayout(this)
        ambient = AmbientBackgroundView(this).apply { motion = prefs.getBoolean("ambient_motion", true) }
        root.addView(ambient, FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        // Android 15+ draws edge-to-edge: keep the UI clear of the status and navigation bars
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            // content runs under the floating dock, so only the top/sides are padded here
            v.setPadding(bars.left, bars.top, bars.right, 0)
            dock.setPadding(ui.dp(12) + bars.left, ui.dp(30), ui.dp(12) + bars.right, ui.dp(10) + bars.bottom)
            systemBars = intArrayOf(bars.left, bars.top, bars.right, bars.bottom)
            fullPlayer?.let { padForBars(it) }; pickSheet?.let { padForBars(it) }
            insets
        }

        column.addView(buildHeader())
        search = EditText(this).apply {
            hint = "Radyo, tür ya da şehir ara"; setTextColor(Neon.TEXT); setHintTextColor(Neon.MUTED); textSize = 15f
            setSingleLine(true); background = ui.glass(18f); setPadding(ui.dp(18), 0, ui.dp(18), 0); visibility = View.GONE
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = searchFor(s?.toString().orEmpty())
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        column.addView(search, LinearLayout.LayoutParams(-1, ui.dp(50)).apply { setMargins(ui.dp(16), 0, ui.dp(16), ui.dp(8)) })

        val pages = FrameLayout(this)
        scroll = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; clipToPadding = false
            descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS // rebuilt rows must not auto-scroll into view
        }
        scrollContent = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, ui.dp(16)) }
        scroll.addView(scrollContent)
        pages.addView(scroll, FrameLayout.LayoutParams(-1, -1))

        listPage = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        listHeader = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listPage.addView(listHeader)
        adapter = StationAdapter({ play(it) }, { isFavorite(it) }, { setFavorite(it, !isFavorite(it), fromList = true) })
        stationList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            this.adapter = this@MainActivity.adapter
            setPadding(ui.dp(9), 0, ui.dp(9), ui.dp(12)); clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER; itemAnimator = null
        }
        listPage.addView(stationList, LinearLayout.LayoutParams(-1, 0, 1f))
        pages.addView(listPage, FrameLayout.LayoutParams(-1, -1))
        column.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))

        dock = buildDock()
        root.addView(dock, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        showPage(Page.HOME)
        return root
    }

    private fun padForBars(view: View) = view.setPadding(systemBars[0], systemBars[1], systemBars[2], systemBars[3])

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(10), ui.dp(12), ui.dp(10)) }
        val logo = ImageView(this).apply {
            setImageResource(R.drawable.keyfe_keder_brand); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) = outline.setRoundRect(0, 0, view.width, view.height, ui.dpf(12f))
            }
            clipToOutline = true
        }
        row.addView(logo, LinearLayout.LayoutParams(ui.dp(42), ui.dp(42)).apply { rightMargin = ui.dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.text("Keyfe Keder", 18f, Neon.TEXT, true).apply { letterSpacing = .02f })
        tagline = ui.text(taglines[0], 12f, Neon.ORANGE)
        texts.addView(tagline)
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.iconButton(R.drawable.ic_search, "Ara", 44, 21) { toggleSearch() }.apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(8) })
        row.addView(ui.iconButton(R.drawable.ic_settings, "Ayarlar", 44, 21) { showPage(Page.SETTINGS) })
        return row
    }

    /**
     * Floating bottom dock: one glass panel holding the mini player and the tabs.
     * Pages scroll underneath it and fade out through a soft scrim instead of ending at a hard edge.
     */
    private fun buildDock(): FrameLayout {
        val holder = FrameLayout(this).apply {
            background = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Neon.withAlpha(Neon.BG, 0), Neon.withAlpha(Neon.BG, 200), Neon.BG))
            setPadding(ui.dp(12), ui.dp(30), ui.dp(12), ui.dp(10))
            clipChildren = false; clipToPadding = false
        }
        dockCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GlassDrawable(this@MainActivity, 30f, 0xF0151119.toInt(), Neon.STROKE)
            elevation = ui.dpf(12f)
        }
        dockCard.addView(buildMiniPlayer(), LinearLayout.LayoutParams(-1, ui.dp(76)))
        dockCard.addView(View(this).apply { setBackgroundColor(0x14FFFFFF) }, LinearLayout.LayoutParams(-1, 1).apply { setMargins(ui.dp(18), 0, ui.dp(18), 0) })
        dockCard.addView(buildNav(), LinearLayout.LayoutParams(-1, ui.dp(66)))
        holder.addView(dockCard, FrameLayout.LayoutParams(-1, -2))
        // pages get enough bottom space to scroll their last row above the dock
        holder.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                val space = bottom - top - ui.dp(14)
                scrollContent.setPadding(0, 0, 0, space)
                stationList.setPadding(ui.dp(9), 0, ui.dp(9), space)
            }
        }
        return holder
    }

    /** Mini player row: cover, song or station, live line, play and next. Tap opens the player, swipe changes station. */
    private fun buildMiniPlayer(): View {
        mini = FrameLayout(this).apply { isClickable = true }
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), 0, ui.dp(12), 0) }
        miniArt = StationArtworkView(this)
        row.addView(miniArt, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { rightMargin = ui.dp(12) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        miniTitle = ui.text("Bir radyo seç", 15f, Neon.TEXT, true).apply {
            isSingleLine = true; ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
        }
        miniSub = ui.text("Keyfime Bırak'a dokun", 12f, Neon.MUTED)
        miniSpectrum = AudioSpectrumView(this)
        info.addView(miniTitle, LinearLayout.LayoutParams(-1, -2))
        info.addView(miniSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
        info.addView(miniSpectrum, LinearLayout.LayoutParams(-1, ui.dp(10)).apply { topMargin = ui.dp(6) })
        row.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        miniPlayIcon = ImageView(this).apply { setImageResource(R.drawable.ic_play); setColorFilter(Neon.TEXT) }
        miniPlay = FrameLayout(this).apply {
            contentDescription = "Çal / duraklat"; isClickable = true
            addView(miniPlayIcon, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.CENTER))
            setOnClickListener { Ui.pop(this); togglePlay() }
        }
        row.addView(miniPlay, LinearLayout.LayoutParams(ui.dp(54), ui.dp(54)).apply { leftMargin = ui.dp(8) })
        row.addView(ui.iconButton(R.drawable.ic_next, "Sonraki radyo", 44, 20) { next() }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = ui.dp(6) })
        mini.addView(row, FrameLayout.LayoutParams(-1, -1))

        var downX = 0f
        mini.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; true }
                MotionEvent.ACTION_MOVE -> { v.translationX = (e.rawX - downX) * .35f; true }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val dx = e.rawX - downX
                    v.animate().translationX(0f).setDuration(220).setInterpolator(OvershootInterpolator(1.5f)).start()
                    if (abs(dx) > ui.dp(70)) { if (dx < 0) next() else previous() }
                    else if (abs(dx) < ui.dp(10) && e.actionMasked == MotionEvent.ACTION_UP) { Ui.pop(v); openFullPlayer() }
                    true
                }
                else -> false
            }
        }
        return mini
    }

    /** Floating glass bar with a neon pill that slides to the selected tab. */
    private fun buildNav(): View {
        val bar = FrameLayout(this).apply { setPadding(ui.dp(8), 0, ui.dp(8), 0) }
        navIndicator = View(this).apply {
            background = GlassDrawable(this@MainActivity, 20f, Neon.ORANGE, 0x40FFFFFF, 0, intArrayOf(0x8CFF7A1A.toInt(), 0x73FF2E88))
            alpha = 0f
        }
        bar.addView(navIndicator, FrameLayout.LayoutParams(0, -1).apply { setMargins(0, ui.dp(7), 0, ui.dp(9)) })
        navRow = LinearLayout(this).apply { gravity = Gravity.CENTER }
        listOf("Ana Sayfa" to Page.HOME, "Radyolar" to Page.RADIOS, "Keşfet" to Page.DISCOVER, "Favoriler" to Page.FAVORITES)
            .forEachIndexed { i, (label, target) ->
                val item = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
                    contentDescription = label
                    setOnClickListener { genreFilter = null; if (search.visibility == View.VISIBLE) toggleSearch(); showPage(target) }
                }
                val iv = ui.icon(navIcons[i].first, Neon.MUTED, 23)
                val tv = ui.text(label, 10.5f, Neon.MUTED, true).apply { gravity = Gravity.CENTER }
                item.addView(iv); item.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(4) })
                navRow.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
                navItems += Triple(item, iv, tv)
            }
        bar.addView(navRow, FrameLayout.LayoutParams(-1, -1))
        bar.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) bar.post { highlightNav(animate = false) } }
        return bar
    }

    private fun navIndex() = when (page) { Page.HOME -> 0; Page.RADIOS, Page.LIST -> 1; Page.DISCOVER -> 2; Page.FAVORITES -> 3; Page.SETTINGS -> -1 }

    private fun highlightNav(animate: Boolean = true) {
        if (!::navIndicator.isInitialized) return
        val active = navIndex()
        navItems.forEachIndexed { i, (item, iv, tv) ->
            val on = i == active
            iv.setImageResource(if (on) navIcons[i].second else navIcons[i].first)
            iv.setColorFilter(if (on) Neon.TEXT else Neon.MUTED)
            tv.setTextColor(if (on) Neon.TEXT else Neon.MUTED)
            if (on && animate) Ui.pop(item)
        }
        val target = navItems.getOrNull(active)?.first
        if (target == null || target.width == 0) { navIndicator.animate().alpha(0f).setDuration(150).start(); return }
        val lp = navIndicator.layoutParams
        val w = target.width - ui.dp(8)
        if (lp.width != w) { lp.width = w; navIndicator.layoutParams = lp }
        val x = target.left + ui.dp(4).toFloat()
        navIndicator.animate().alpha(1f).setDuration(150).start()
        if (animate) navIndicator.animate().translationX(x).setDuration(320).setInterpolator(OvershootInterpolator(1.1f)).start()
        else navIndicator.translationX = x
    }

    // ---------------------------------------------------------------- pages

    private fun showPage(target: Page, keepScroll: Boolean = false) {
        val changed = target != page
        page = target
        highlightNav(animate = changed)
        when (target) {
            Page.HOME -> scrollPage(keepScroll) { buildHome() }
            Page.DISCOVER -> scrollPage(keepScroll) { buildDiscover() }
            Page.SETTINGS -> scrollPage(keepScroll) { buildSettings() }
            Page.RADIOS -> listPage("Tüm radyolar", "${stations.size} canlı yayın", stations, showGenres = true)
            Page.FAVORITES -> listPage("Favorilerin", "Kalbe dokunduğun radyolar burada", stations.filter { isFavorite(it) }, showGenres = false,
                empty = "Henüz favorin yok. Bir radyonun kalbine dokun, burada parlasın.")
            Page.LIST -> listPage(listTitle, "${listItems.size} radyo", listItems, showGenres = false)
        }
    }

    private fun scrollPage(keepScroll: Boolean, build: () -> Unit) {
        listPage.visibility = View.GONE; scroll.visibility = View.VISIBLE
        val y = scroll.scrollY
        scrollContent.removeAllViews(); build()
        if (keepScroll) { scroll.post { scroll.scrollTo(0, y) }; return }
        scroll.scrollTo(0, 0); scroll.post { scroll.scrollTo(0, 0) }
        scrollContent.alpha = 0f; scrollContent.translationY = ui.dpf(12f)
        scrollContent.animate().alpha(1f).translationY(0f).setDuration(280).start()
    }

    private fun listPage(title: String, subtitle: String, items: List<Station>, showGenres: Boolean, empty: String? = null) {
        scroll.visibility = View.GONE; listPage.visibility = View.VISIBLE
        listHeader.removeAllViews()
        listHeader.addView(ui.text(title, 26f, Neon.TEXT, true).apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) })
        listHeader.addView(ui.text(subtitle, 13f, Neon.MUTED).apply { setPadding(ui.dp(20), ui.dp(4), ui.dp(20), ui.dp(10)) })
        var shown = items
        if (showGenres) {
            val genres = stations.groupingBy { it.genre.ifBlank { "Radyo" } }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
            val chips = listOf(ui.chip("Tümü", selected = genreFilter == null) { genreFilter = null; showPage(Page.RADIOS) }) +
                genres.map { g -> ui.chip(g, selected = g == genreFilter) { genreFilter = g; showPage(Page.RADIOS) } }
            listHeader.addView(ui.chipRow(chips), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(6) })
            genreFilter?.let { g -> shown = items.filter { it.genre.ifBlank { "Radyo" } == g } }
        }
        if (shown.isEmpty() && empty != null) listHeader.addView(ui.empty(empty))
        adapter.submitList(shown)
        stationList.scrollToPosition(0)
    }

    private fun buildHome() {
        val c = scrollContent
        c.addView(ui.label(greeting()).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) })
        c.addView(ui.text("Bugün hangi frekanstasın?", 28f, Neon.TEXT, true, lines = 2).apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) })
        c.addView(heroPickCard(), LinearLayout.LayoutParams(-1, ui.dp(112)).apply { setMargins(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(4)) })

        c.addView(ui.sectionHeader("Hemen bir mod seç"))
        c.addView(ui.chipRow(moodChips()))

        val recent = historyStations()
        c.addView(ui.sectionHeader("Son dinlediklerin"))
        if (recent.isEmpty()) c.addView(ui.empty("Henüz birlikte bir radyo dinlemedik. Keyfime Bırak'a bir dokun.")) else c.addView(cardRow(recent))

        val favs = stations.filter { isFavorite(it) }
        c.addView(ui.sectionHeader("Favorilerin", if (favs.isNotEmpty()) "Tümü" else null) { showPage(Page.FAVORITES) })
        if (favs.isEmpty()) c.addView(ui.empty("Sevdiğin radyoların kalbine dokun, buraya gelsinler.")) else c.addView(cardRow(favs.take(12)))

        c.addView(ui.sectionHeader("Türler", "Tümü") { showPage(Page.RADIOS) })
        c.addView(ui.chipRow(genreChips()))

        val daily = stations.filter { it.logoUrl.isNotBlank() }.shuffled(java.util.Random(dayOfYear().toLong())).take(10)
        if (daily.isNotEmpty()) {
            c.addView(ui.sectionHeader("Bugünün seçkisi"))
            c.addView(cardRow(daily))
        }
    }

    private fun buildDiscover() {
        val c = scrollContent
        c.addView(ui.text("Keşfet", 28f, Neon.TEXT, true).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) })
        c.addView(ui.text("Hep aynı frekansta takılmak yok. Yeni sesler bulalım.", 13.5f, Neon.MUTED, lines = 2).apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0) })
        c.addView(ui.sectionHeader("Ruh haline göre"))
        c.addView(ui.chipRow(moodChips()))
        c.addView(ui.sectionHeader("Türler"))
        c.addView(genreGrid())
        val history = recentUrls().toSet()
        val fresh = stations.filter { it.resolvedUrl !in history }.shuffled(java.util.Random(dayOfYear() * 31L)).take(10)
        c.addView(ui.sectionHeader("Daha önce dinlemediklerin"))
        if (fresh.isEmpty()) c.addView(ui.empty("Hepsini denemişsin! O zaman Keyfime Bırak'a teslim ol.")) else c.addView(cardRow(fresh))
    }

    private fun buildSettings() {
        val c = scrollContent
        c.addView(ui.text("Ayarlar", 28f, Neon.TEXT, true).apply { setPadding(ui.dp(20), ui.dp(8), ui.dp(20), 0) })
        c.addView(ui.text("Radyonu kendine göre ayarla.", 13.5f, Neon.MUTED).apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(4)) })

        c.addView(ui.sectionHeader("Uyku zamanlayıcısı"))
        val sleep = settingsCard()
        sleep.addView(settingRow(R.drawable.ic_timer, "Yayını otomatik durdur", sleepLabel()?.let { "$it sonra duracak" } ?: "Kapalı", null))
        val until = prefs.getLong("sleep_until", 0L)
        val left = if (until > System.currentTimeMillis()) (until - System.currentTimeMillis()) / 60_000L + 1 else 0L
        val options = listOf(0L to "Kapalı", 15L to "15 dk", 30L to "30 dk", 45L to "45 dk", 60L to "1 saat", 90L to "1,5 saat")
        val chosen = if (left == 0L) 0L else options.filter { it.first > 0 }.minByOrNull { abs(it.first - left) }?.first ?: 0L
        sleep.addView(ui.chipRow(options.map { (m, label) -> ui.chip(label, selected = m == chosen) { setSleep(m) } }),
            LinearLayout.LayoutParams(-1, -2).apply { setMargins(-ui.dp(16), 0, -ui.dp(16), ui.dp(8)) })
        c.addView(wrap(sleep))

        c.addView(ui.sectionHeader("Oynatma ve görünüm"))
        val play = settingsCard()
        play.addView(settingRow(R.drawable.ic_play_circle, "Açılışta son radyoyu çal", "Uygulamayı açınca kaldığın yerden devam et",
            NeonSwitch(this, prefs.getBoolean("autoplay_last", false)) { prefs.edit().putBoolean("autoplay_last", it).apply() }))
        play.addView(divider())
        play.addView(settingRow(R.drawable.ic_sparkle, "Hareketli arka plan", "Müzikle nefes alan neon ışıklar",
            NeonSwitch(this, ambient.motion) { prefs.edit().putBoolean("ambient_motion", it).apply(); ambient.motion = it }))
        c.addView(wrap(play))

        c.addView(ui.sectionHeader("Bildirimler"))
        val notes = settingsCard()
        Reminders.Slot.entries.forEachIndexed { i, slot ->
            if (i > 0) notes.addView(divider())
            val title = if (slot == Reminders.Slot.MORNING) "Sabah hatırlatıcısı" else "Akşam hatırlatıcısı"
            val icon = if (slot == Reminders.Slot.MORNING) R.drawable.ic_sun else R.drawable.ic_moon
            notes.addView(settingRow(icon, title, "${slot.label} • favori radyonla kısa bir selam",
                NeonSwitch(this, Reminders.isEnabled(this, slot)) { on ->
                    Reminders.setEnabled(this, slot, on)
                    if (on) ensureNotificationsAllowed()
                }).apply {
                    // debug builds only: long-press shows the reminder right away for testing
                    if (BuildConfig.DEBUG) setOnLongClickListener { executor.execute { Reminders.preview(applicationContext, slot) }; true }
                })
        }
        c.addView(wrap(notes))

        c.addView(ui.sectionHeader("Radyolar"))
        val data = settingsCard()
        data.addView(settingRow(R.drawable.ic_refresh, "Radyo listesini yenile", "${stations.size} radyo yüklü", chevron()).apply {
            setOnClickListener { Ui.pop(this); toast("Liste yenileniyor…"); loadStations(force = true) }
        })
        data.addView(divider())
        data.addView(settingRow(R.drawable.ic_history, "Son dinlenenleri temizle", "${recentUrls().size} radyo", chevron()).apply {
            setOnClickListener {
                Ui.pop(this)
                AlertDialog.Builder(this@MainActivity).setMessage("Son dinlenenler listesi temizlensin mi?")
                    .setPositiveButton("Temizle") { _, _ -> prefs.edit().remove("history").apply(); toast("Temizlendi"); showPage(Page.SETTINGS, keepScroll = true) }
                    .setNegativeButton("Vazgeç", null).show()
            }
        })
        c.addView(wrap(data))

        c.addView(ui.sectionHeader("Hakkında"))
        val about = settingsCard()
        about.addView(settingRow(R.drawable.ic_info, "Keyfe Keder Radyo", "Sürüm ${BuildConfig.VERSION_NAME}", null))
        about.addView(divider())
        about.addView(settingRow(R.drawable.ic_shield, "Gizlilik", "Hesap yok. Favorilerin ve ayarların yalnızca bu cihazda.", null))
        c.addView(wrap(about))
    }

    private fun settingsCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4)); background = ui.glass(22f)
    }

    private fun wrap(card: View): View = FrameLayout(this).apply {
        setPadding(ui.dp(16), 0, ui.dp(16), 0); addView(card, FrameLayout.LayoutParams(-1, -2))
    }

    private fun settingRow(icon: Int, title: String, detail: String, trailing: View?): LinearLayout {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, ui.dp(12), 0, ui.dp(12)); isClickable = trailing != null }
        val badge = FrameLayout(this).apply {
            background = GlassDrawable(this@MainActivity, 14f, 0, 0x33FFFFFF, 0, intArrayOf(0x40FF7A1A, 0x33FF2E88))
            addView(ui.icon(icon, Neon.ORANGE, 20), FrameLayout.LayoutParams(ui.dp(20), ui.dp(20), Gravity.CENTER))
        }
        row.addView(badge, LinearLayout.LayoutParams(ui.dp(40), ui.dp(40)).apply { rightMargin = ui.dp(14) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.text(title, 15f, Neon.TEXT, true))
        texts.addView(ui.text(detail, 12.5f, Neon.MUTED, lines = 2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        if (trailing is NeonSwitch) {
            row.addView(trailing, LinearLayout.LayoutParams(ui.dp(56), ui.dp(34)))
            row.setOnClickListener { trailing.toggle() }
        } else if (trailing != null) row.addView(trailing)
        return row
    }

    private fun chevron() = ui.icon(R.drawable.ic_chevron_right, Neon.MUTED, 18)
    private fun divider() = View(this).apply { setBackgroundColor(0x14FFFFFF); layoutParams = LinearLayout.LayoutParams(-1, 1) }

    // ---------------------------------------------------------------- building blocks

    private fun heroPickCard(): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(18), ui.dp(14), ui.dp(16), ui.dp(14)); isClickable = true
            background = ui.glass(28f, Neon.withAlpha(Neon.ORANGE, 110), gradient = intArrayOf(0x66FF7A1A, 0x4DFF2E88, 0x268B5CFF))
            setOnClickListener { Ui.pop(this); openPick() }
        }
        val badge = FrameLayout(this).apply {
            background = GlassDrawable(this@MainActivity, 26f, Neon.ORANGE, 0x55FFFFFF, Neon.withAlpha(Neon.ORANGE, 160), Neon.BRAND)
            addView(ui.icon(R.drawable.ic_shuffle, Neon.TEXT, 26), FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
        }
        card.addView(badge, LinearLayout.LayoutParams(ui.dp(58), ui.dp(58)).apply { rightMargin = ui.dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.text("Keyfime bırak", 20f, Neon.TEXT, true))
        texts.addView(ui.text(pickSubtitle(), 13f, 0xFFFFE2CC.toInt(), lines = 2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(ui.icon(R.drawable.ic_chevron_right, Neon.TEXT, 22))
        return card
    }

    private fun moodChips() = listOf(
        R.drawable.ic_moon to "Sakin", R.drawable.ic_heart_break to "Dertli", R.drawable.ic_bolt to "Enerjik",
        R.drawable.ic_car to "Yoldayım", R.drawable.ic_headphones to "Kafamı dinliyorum"
    ).map { (icon, mood) -> ui.chip(mood, icon) { showMood(mood) } }

    private fun genreChips(): List<View> = stations.groupingBy { it.genre.ifBlank { "Radyo" } }.eachCount().entries
        .sortedByDescending { it.value }.map { (genre, count) -> ui.chip("$genre  $count") { genreFilter = genre; showPage(Page.RADIOS) } }

    private fun genreGrid(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, ui.dp(12), 0) }
        val genres = stations.groupingBy { it.genre.ifBlank { "Radyo" } }.eachCount().entries.sortedByDescending { it.value }.take(8)
        genres.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEach { (genre, count) ->
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(14)); isClickable = true
                    background = ui.glass(22f, gradient = intArrayOf(0x40FF7A1A, 0x1FFF2E88, 0x0DFFFFFF))
                    setOnClickListener { Ui.pop(this); genreFilter = genre; showPage(Page.RADIOS) }
                }
                tile.addView(ui.icon(R.drawable.ic_waveform, Neon.ORANGE, 22).apply { (layoutParams as LinearLayout.LayoutParams).bottomMargin = ui.dp(10) })
                tile.addView(ui.text(genre, 17f, Neon.TEXT, true))
                tile.addView(ui.text("$count radyo", 12f, Neon.MUTED))
                row.addView(tile, LinearLayout.LayoutParams(0, ui.dp(104), 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
            }
            if (pair.size == 1) row.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
            grid.addView(row)
        }
        return grid
    }

    private fun cardRow(list: List<Station>): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(10)) }
        val playing = controller?.currentMediaItem?.mediaId
        list.forEach { st ->
            val active = st.resolvedUrl == playing && controller?.isPlaying == true
            val card = FrameLayout(this).apply {
                isClickable = true
                background = if (active) ui.glass(24f, Neon.withAlpha(Neon.ORANGE, 150), gradient = intArrayOf(0x47FF7A1A, 0x26FF2E88)) else ui.glass(24f)
                setOnClickListener { Ui.pop(this); play(st) }
            }
            val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(12), ui.dp(16), ui.dp(12), ui.dp(12)) }
            column.addView(StationArtworkView(this).apply { bind(st.name, st.genre, st.logoUrl); setPlaying(active) },
                LinearLayout.LayoutParams(ui.dp(104), ui.dp(104)).apply { bottomMargin = ui.dp(10) })
            column.addView(ui.text(st.name, 13.5f, Neon.TEXT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
            column.addView(ui.text(if (active) "● Canlı" else st.genre.ifBlank { "Radyo" }, 11.5f, if (active) Neon.ORANGE else Neon.MUTED).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
            card.addView(column, FrameLayout.LayoutParams(-1, -1))
            val fav = FavoriteButton(this, 19f).apply { setOn(isFavorite(st)) }
            fav.setOnClickListener { fav.animateTo(!fav.isOn); setFavorite(st, fav.isOn, fromList = true) }
            card.addView(fav, FrameLayout.LayoutParams(ui.dp(40), ui.dp(40), Gravity.TOP or Gravity.END))
            row.addView(card, LinearLayout.LayoutParams(ui.dp(148), ui.dp(194)).apply { setMargins(ui.dp(5), ui.dp(4), ui.dp(5), ui.dp(4)) })
        }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(row)
        }
    }

    // ---------------------------------------------------------------- actions

    private fun showMood(mood: String) {
        if (stations.isEmpty()) return toast("Radyolar henüz yükleniyor.")
        val list = RadioMoodMatcher.rank(stations, mood)
        if (list.isEmpty()) return toast("Bu moda uygun radyo bulamadım. Keyfime Bırak'ı dene.")
        listTitle = mood; listItems = list; showPage(Page.LIST)
    }

    private fun searchFor(query: String) {
        val q = query.trim()
        if (q.isBlank()) { if (page == Page.LIST) showPage(Page.RADIOS); return }
        listTitle = "\"$q\""
        listItems = stations.filter { it.name.contains(q, true) || it.genre.contains(q, true) || it.country.contains(q, true) || it.language.contains(q, true) }
        showPage(Page.LIST)
    }

    private fun toggleSearch() {
        val imm = getSystemService(InputMethodManager::class.java)
        if (search.visibility == View.VISIBLE) {
            search.setText(""); search.visibility = View.GONE; imm.hideSoftInputFromWindow(search.windowToken, 0)
        } else {
            search.visibility = View.VISIBLE; search.requestFocus(); imm.showSoftInput(search, 0)
        }
    }

    private fun openPick() {
        if (stations.isEmpty()) return toast("Radyolar henüz yükleniyor.")
        if (pickSheet != null) return
        pickSheet = PickSheetView(this, { stations }, { mood, exclude ->
            StationPicker.pick(stations, favorites(), recentUrls(), brokenUrls(), Calendar.getInstance().get(Calendar.HOUR_OF_DAY), mood, exclude)
        }, { play(it) }, { pickSheet = null }).also { padForBars(it); it.show(root) }
    }

    private fun openFullPlayer() {
        val state = nowPlaying()
        if (state.station == null) { openPick(); return }
        if (fullPlayer != null) return
        fullPlayer = FullPlayerView(this,
            onClose = { closeFullPlayer() }, onToggle = { togglePlay() }, onPrev = { previous() }, onNext = { next() },
            onFavorite = { nowPlaying().station?.let { setFavorite(it, !isFavorite(it), fromList = false) } },
            onSleep = { chooseSleepTimer() },
            onShare = { shareNowPlaying() },
            onOutput = { openOutputs() }
        ).also { padForBars(it); it.show(root); it.bind(state) }
    }

    private fun closeFullPlayer() { fullPlayer?.hide { fullPlayer = null } }

    private fun openOutputs() {
        if (outputSheet != null) return
        outputSheet = AudioOutputSheet(this, { device ->
            controller?.sendCustomCommand(
                androidx.media3.session.SessionCommand(RadioPlaybackService.ACTION_OUTPUT, Bundle.EMPTY),
                Bundle().apply { putInt(RadioPlaybackService.EXTRA_DEVICE_ID, device?.id ?: -1) })
            handler.postDelayed({ refreshPlayerUi() }, 300)
        }, { outputSheet = null; refreshPlayerUi() }).also { padForBars(it); it.show(root) }
    }

    private fun goBack() {
        when {
            outputSheet != null -> outputSheet?.close()
            pickSheet != null -> pickSheet?.close()
            fullPlayer != null -> closeFullPlayer()
            search.visibility == View.VISIBLE -> toggleSearch()
            page != Page.HOME -> { genreFilter = null; showPage(Page.HOME) }
            else -> showExitDialog()
        }
    }

    private fun shareNowPlaying() {
        val np = nowPlaying(); val st = np.station ?: return
        val text = if (!np.song.isNullOrBlank()) "Keyfe Keder Radyo'da ${st.name} dinliyorum: ${listOfNotNull(np.artist?.takeIf { it != StationMedia.LIVE }, np.song).joinToString(" – ")}"
        else "Keyfe Keder Radyo'da ${st.name} dinliyorum."
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Paylaş"))
    }

    private fun chooseSleepTimer() {
        val options = arrayOf("Kapalı", "15 dakika", "30 dakika", "45 dakika", "1 saat", "1,5 saat")
        val minutes = longArrayOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(this).setTitle("Uyku zamanlayıcısı").setItems(options) { _, which -> setSleep(minutes[which]) }.show()
    }

    private fun setSleep(minutes: Long) {
        prefs.edit().putLong("sleep_until", if (minutes == 0L) 0L else System.currentTimeMillis() + minutes * 60_000L).apply()
        toast(if (minutes == 0L) "Uyku zamanlayıcısı kapatıldı." else "$minutes dakika sonra yayını durduracağım.")
        refreshPlayerUi()
        if (page == Page.SETTINGS) showPage(Page.SETTINGS, keepScroll = true)
    }

    private fun sleepLabel(): String? {
        val until = prefs.getLong("sleep_until", 0L)
        if (until <= System.currentTimeMillis()) return null
        return "${((until - System.currentTimeMillis()) / 60_000L + 1).coerceAtLeast(1)} dk"
    }

    private fun showExitDialog() {
        if (controller?.isPlaying != true) { finish(); return }
        AlertDialog.Builder(this).setTitle("Keyfe Keder Radyo")
            .setMessage("Yayın arka planda çalmaya devam etsin mi?")
            .setPositiveButton("Arka planda çal") { _, _ -> moveTaskToBack(true) }
            .setNegativeButton("Kapat") { _, _ -> controller?.stop(); controller?.clearMediaItems(); finish() }
            .setNeutralButton("Vazgeç", null).show()
    }

    // ---------------------------------------------------------------- playback

    private fun connectPlayer() {
        val token = SessionToken(this, ComponentName(this, RadioPlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync().also { future ->
            future.addListener({
                val c = runCatching { future.get() }.getOrNull() ?: return@addListener toast("Oynatıcıya bağlanılamadı.")
                controller = c
                c.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events) {
                        if (events.containsAny(Player.EVENT_IS_PLAYING_CHANGED, Player.EVENT_PLAYBACK_STATE_CHANGED,
                                Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_MEDIA_METADATA_CHANGED, Player.EVENT_PLAYER_ERROR)) {
                            if (events.contains(Player.EVENT_IS_PLAYING_CHANGED) && player.isPlaying) playerError = false
                            refreshPlayerUi()
                        }
                        // the "now playing" glow on home cards follows the station
                        if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED) && (page == Page.HOME || page == Page.DISCOVER))
                            showPage(page, keepScroll = true)
                    }
                    override fun onPlayerError(error: PlaybackException) { playerError = true; refreshPlayerUi() }
                })
                refreshPlayerUi()
                playPending()
                maybeAutoplay()
            }, ContextCompat.getMainExecutor(this))
        }
    }

    /** "Açılışta son radyoyu çal": once per launch, when nothing is playing yet. */
    private fun maybeAutoplay() {
        val c = controller ?: return
        if (autoplayDone || stations.isEmpty() || !prefs.getBoolean("autoplay_last", false)) return
        autoplayDone = true
        if (c.isPlaying || c.currentMediaItem != null) return
        val last = recentUrls().firstOrNull() ?: return
        stations.firstOrNull { it.resolvedUrl == last }?.let { play(it) }
    }

    private fun play(station: Station) {
        val c = controller ?: return toast("Oynatıcı hazırlanıyor, bir saniye…")
        requestNotificationPermission()
        rememberStation(station)
        playerError = false
        c.setMediaItem(StationMedia.toMediaItem(this, station))
        c.prepare(); c.play()
        refreshPlayerUi()
        miniArt.animate().scaleX(1.12f).scaleY(1.12f).setDuration(140).withEndAction { miniArt.animate().scaleX(1f).scaleY(1f).setDuration(220).start() }.start()
    }

    private fun togglePlay() {
        val c = controller ?: return
        when {
            c.currentMediaItem == null -> openPick()
            c.isPlaying -> c.pause()
            else -> { if (c.playbackState == Player.STATE_IDLE) c.prepare(); c.play() }
        }
    }

    private fun next() = step(1)
    private fun previous() = step(-1)

    private fun step(delta: Int) {
        val c = controller ?: return
        if (c.currentMediaItem == null) { openPick(); return }
        if (c.mediaItemCount > 1) {
            if (delta > 0) c.seekToNextMediaItem() else c.seekToPreviousMediaItem()
            c.prepare(); c.play()
            nowPlaying().station?.let { rememberStation(it) }
            return
        }
        if (stations.isEmpty()) return
        val index = stations.indexOfFirst { it.resolvedUrl == c.currentMediaItem?.mediaId }
        play(stations[((if (index < 0) 0 else index + delta) + stations.size) % stations.size])
    }

    private fun nowPlaying(): NowPlayingUi {
        val c = controller
        val id = c?.currentMediaItem?.mediaId
        val station = stations.firstOrNull { it.resolvedUrl == id }
        val meta: MediaMetadata? = c?.mediaMetadata
        val hasTrack = meta?.extras?.getBoolean(StationMedia.EXTRA_HAS_TRACK) == true
        return NowPlayingUi(
            station = station,
            song = if (hasTrack) meta?.title?.toString() else null,
            artist = if (hasTrack) meta?.artist?.toString() else null,
            playing = c?.isPlaying == true,
            buffering = c?.playbackState == Player.STATE_BUFFERING,
            error = playerError,
            favorite = station?.let { isFavorite(it) } == true,
            sleepLabel = sleepLabel(),
        )
    }

    private fun refreshPlayerUi(animateFavorite: Boolean = false) {
        if (isFinishing || isDestroyed) return
        val np = nowPlaying()
        val st = np.station
        if (st == null) {
            miniArt.bind("RADYO", ""); miniArt.setPlaying(false)
            miniTitle.text = "Bir radyo seç"; miniSub.text = "Keyfime Bırak'a dokun"; miniSub.setTextColor(Neon.MUTED)
            miniSpectrum.setPlaying(false); miniSpectrum.visibility = View.INVISIBLE
        } else {
            miniArt.bind(st.name, st.genre, st.logoUrl); miniArt.setPlaying(np.playing)
            val title = np.song ?: st.name
            if (miniTitle.text.toString() != title) miniTitle.text = title
            miniSub.text = when {
                np.error -> "Yayına ulaşılamadı, yeniden deneniyor…"
                np.buffering -> "Bağlanıyor…"
                np.song != null -> listOfNotNull(np.artist?.takeIf { it != StationMedia.LIVE }, st.name).joinToString(" • ")
                np.playing -> "● Canlı yayın"
                else -> "Duraklatıldı"
            }
            miniSub.setTextColor(if (np.playing && np.song == null) Neon.ORANGE else Neon.MUTED)
            miniSpectrum.setPlaying(np.playing)
            miniSpectrum.visibility = if (np.playing) View.VISIBLE else View.INVISIBLE
        }
        miniPlayIcon.setImageResource(if (np.playing || np.buffering) R.drawable.ic_pause else R.drawable.ic_play)
        miniPlay.background = GlassDrawable(this, 27f, Neon.ORANGE, 0x55FFFFFF, 0, Neon.BRAND)
        dockCard.background = GlassDrawable(this, 30f, 0xF0151119.toInt(), if (np.playing) 0x88FF7A1A.toInt() else Neon.STROKE)
        fullPlayer?.bind(np, animateFavorite)
    }

    // ---------------------------------------------------------------- data

    private fun loadStations(force: Boolean = false) {
        val repo = StationRepository(applicationContext)
        executor.execute {
            // Show the cached/bundled list immediately, then swap in the fresh one if it downloads
            if (!force) runCatching { repo.loadLocal() }.getOrNull()?.let { local -> runOnUiThread { applyStations(local) } }
            val fresh = runCatching { repo.refresh() }.getOrNull()
            runOnUiThread {
                if (fresh != null) applyStations(fresh, announce = force)
                else if (force) toast("İnternete ulaşılamadı, kayıtlı liste kullanılıyor.")
            }
        }
    }

    private fun applyStations(loaded: List<Station>, announce: Boolean = false) {
        if (isFinishing || isDestroyed || loaded.isEmpty()) return
        if (announce) toast("${loaded.size} radyo güncel.")
        if (loaded == stations) { if (announce && page == Page.SETTINGS) showPage(Page.SETTINGS, keepScroll = true); return }
        stations = loaded
        if (page == Page.LIST && search.text.isNotBlank()) searchFor(search.text.toString()) else showPage(page, keepScroll = page == Page.SETTINGS)
        refreshPlayerUi()
        playPending()
        maybeAutoplay()
    }

    private fun favorites() = stations.filter { isFavorite(it) }.map { it.resolvedUrl }.toSet()
    private fun recentUrls() = prefs.getString("history", "").orEmpty().split("|").filter { it.isNotBlank() }
    private fun brokenUrls(): Set<String> {
        val cutoff = System.currentTimeMillis() - 6 * 3600_000L
        return prefs.all.filter { (k, v) -> k.startsWith("bad_") && (v as? Long ?: 0L) > cutoff }.keys.map { it.removePrefix("bad_") }.toSet()
    }
    private fun historyStations() = recentUrls().mapNotNull { url -> stations.firstOrNull { it.resolvedUrl == url } }.take(10)

    private fun rememberStation(station: Station) {
        val urls = recentUrls().filter { it != station.resolvedUrl }.toMutableList()
        urls.add(0, station.resolvedUrl)
        prefs.edit().putString("history", urls.take(12).joinToString("|")).apply()
    }

    private fun isFavorite(station: Station) = prefs.getBoolean(station.resolvedUrl, false)

    /**
     * [fromList] = the heart on a card was tapped: that heart already animates itself,
     * so pages that list favourites are rebuilt only after the burst has played.
     */
    private fun setFavorite(station: Station, on: Boolean, fromList: Boolean) {
        prefs.edit().putBoolean(station.resolvedUrl, on).apply()
        refreshPlayerUi(animateFavorite = !fromList)
        if (page == Page.FAVORITES || page == Page.HOME) handler.postDelayed({ if (!isFinishing) showPage(page, keepScroll = true) }, 650)
    }

    // ---------------------------------------------------------------- misc

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        }
    }

    /** Reminders need the notification permission; ask, or send the user to the app's notification settings. */
    private fun ensureNotificationsAllowed() {
        if (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (!prefs.getBoolean("asked_notifications", false) || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        } else {
            toast("Bildirimler kapalı. Açmak için ayarlara götürüyorum.")
            startActivity(Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
        in 5..11 -> "Günaydın"
        in 12..17 -> "İyi günler"
        in 18..22 -> "İyi akşamlar"
        else -> "İyi geceler"
    }

    private fun pickSubtitle(): String = when (StationPicker.moodForHour(Calendar.getInstance().get(Calendar.HOUR_OF_DAY))) {
        "Kafamı dinliyorum" -> "Gecenin bu saatine sakin bir frekans seçeyim."
        "Enerjik" -> "Enerjini yükseltecek bir yayın bulayım."
        "Yoldayım" -> "Gün akarken eşlik edecek bir radyo bulayım."
        else -> "Akşamın keyfine göre bir frekans seçeyim."
    }

    private fun dayOfYear() = Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
