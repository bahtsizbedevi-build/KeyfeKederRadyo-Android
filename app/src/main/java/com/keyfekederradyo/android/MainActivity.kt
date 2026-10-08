package com.keyfekederradyo.android

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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

class MainActivity : AppCompatActivity() {
    private val ui by lazy { Ui(this) }
    private val prefs by lazy { getSharedPreferences("radio", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())

    private var stations = emptyList<Station>()
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var playerError = false

    private enum class Page { HOME, RADIOS, DISCOVER, FAVORITES, SONGS, LIST }
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
    private lateinit var mini: LinearLayout
    private lateinit var miniArt: StationArtworkView
    private lateinit var miniTitle: TextView
    private lateinit var miniSub: TextView
    private lateinit var miniSpectrum: AudioSpectrumView
    private lateinit var miniPlayIcon: ImageView
    private lateinit var miniPlay: FrameLayout
    private val navItems = mutableListOf<Triple<View, ImageView, TextView>>()
    private var fullPlayer: FullPlayerView? = null
    private var pickSheet: PickSheetView? = null

    private var systemBars = intArrayOf(0, 0, 0, 0)
    /** Overlays cover the whole window (behind the bars) but keep their content clear of them. */
    private fun padForBars(view: View) = view.setPadding(systemBars[0], systemBars[1], systemBars[2], systemBars[3])

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
        connectPlayer()
        loadStations()
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

    // ---------------------------------------------------------------- layout

    private fun buildUi(): View {
        root = FrameLayout(this)
        ambient = AmbientBackgroundView(this)
        root.addView(ambient, FrameLayout.LayoutParams(-1, -1))

        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(column, FrameLayout.LayoutParams(-1, -1))
        // Android 15+ draws edge-to-edge: keep the UI clear of the status and navigation bars
        ViewCompat.setOnApplyWindowInsetsListener(column) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
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
        adapter = StationAdapter({ play(it) }, { isFavorite(it) }, { toggleFavorite(it) })
        stationList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            this.adapter = this@MainActivity.adapter
            setPadding(ui.dp(9), 0, ui.dp(9), ui.dp(12)); clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER; itemAnimator = null
        }
        listPage.addView(stationList, LinearLayout.LayoutParams(-1, 0, 1f))
        pages.addView(listPage, FrameLayout.LayoutParams(-1, -1))
        column.addView(pages, LinearLayout.LayoutParams(-1, 0, 1f))

        column.addView(buildMiniPlayer(), LinearLayout.LayoutParams(-1, ui.dp(74)).apply { setMargins(ui.dp(12), ui.dp(6), ui.dp(12), ui.dp(8)) })
        column.addView(buildNav(), LinearLayout.LayoutParams(-1, ui.dp(66)).apply { setMargins(ui.dp(12), 0, ui.dp(12), ui.dp(8)) })
        showPage(Page.HOME)
        return root
    }

    private fun buildHeader(): View {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(16), ui.dp(10), ui.dp(10), ui.dp(10)) }
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
        row.addView(ui.iconButton(R.drawable.ic_search, "Ara", 44, 22) { toggleSearch() }.apply { (layoutParams as LinearLayout.LayoutParams).rightMargin = ui.dp(8) })
        row.addView(ui.iconButton(R.drawable.ic_settings, "Ayarlar", 44, 22) { showSettings() })
        return row
    }

    private fun buildMiniPlayer(): View {
        mini = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(10), ui.dp(8), ui.dp(10), ui.dp(8))
            background = ui.glass(24f, fill = 0x2EFFFFFF); isClickable = true
            setOnClickListener { openFullPlayer() }
        }
        miniArt = StationArtworkView(this)
        mini.addView(miniArt, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { rightMargin = ui.dp(12) })
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        miniTitle = ui.text("Bir radyo seç", 14.5f, Neon.TEXT, true)
        miniSub = ui.text("Keyfime Bırak'ı dene", 12f, Neon.MUTED)
        miniSpectrum = AudioSpectrumView(this)
        info.addView(miniTitle); info.addView(miniSub, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(2) })
        info.addView(miniSpectrum, LinearLayout.LayoutParams(-1, ui.dp(14)).apply { topMargin = ui.dp(5) })
        mini.addView(info, LinearLayout.LayoutParams(0, -1, 1f))
        miniPlayIcon = ImageView(this).apply { setImageResource(R.drawable.ic_play); setColorFilter(Neon.TEXT) }
        miniPlay = FrameLayout(this).apply {
            contentDescription = "Çal / duraklat"; isClickable = true
            addView(miniPlayIcon, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.CENTER))
            setOnClickListener { Ui.pop(this); togglePlay() }
        }
        mini.addView(miniPlay, LinearLayout.LayoutParams(ui.dp(50), ui.dp(50)).apply { leftMargin = ui.dp(8) })
        mini.addView(ui.iconButton(R.drawable.ic_next, "Sonraki radyo", 44, 22) { next() }.apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = ui.dp(6) })
        return mini
    }

    private fun buildNav(): View {
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6)); background = ui.glass(26f, fill = 0x24FFFFFF) }
        listOf(Triple(R.drawable.ic_home, "Ana Sayfa", Page.HOME), Triple(R.drawable.ic_radio, "Radyolar", Page.RADIOS),
            Triple(R.drawable.ic_explore, "Keşfet", Page.DISCOVER), Triple(R.drawable.ic_heart, "Favoriler", Page.FAVORITES)).forEach { (icon, label, target) ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true
                setOnClickListener { Ui.pop(this); search.setText(""); showPage(target) }
            }
            val iv = ui.icon(icon, Neon.MUTED, 22)
            val tv = ui.text(label, 10.5f, Neon.MUTED, true).apply { gravity = Gravity.CENTER }
            item.addView(iv); item.addView(tv, LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(3) })
            nav.addView(item, LinearLayout.LayoutParams(0, -1, 1f))
            navItems += Triple(item, iv, tv)
        }
        return nav
    }

    private fun highlightNav() {
        val active = when (page) { Page.HOME -> 0; Page.RADIOS, Page.LIST -> 1; Page.DISCOVER, Page.SONGS -> 2; Page.FAVORITES -> 3 }
        navItems.forEachIndexed { i, (item, iv, tv) ->
            val on = i == active
            item.background = if (on) ui.glass(20f, Neon.withAlpha(Neon.ORANGE, 70), gradient = intArrayOf(0x40FF7A1A, 0x26FF2E88)) else null
            iv.setColorFilter(if (on) Neon.ORANGE else Neon.MUTED)
            tv.setTextColor(if (on) Neon.TEXT else Neon.MUTED)
        }
    }

    // ---------------------------------------------------------------- pages

    private fun showPage(target: Page) {
        page = target
        highlightNav()
        when (target) {
            Page.HOME -> scrollPage { buildHome() }
            Page.DISCOVER -> scrollPage { buildDiscover() }
            Page.SONGS -> scrollPage { buildSongs() }
            Page.RADIOS -> listPage("Tüm radyolar", "${stations.size} canlı yayın", stations, showGenres = true)
            Page.FAVORITES -> listPage("Favorilerin", "Kalbe dokunduğun radyolar burada", stations.filter { isFavorite(it) }, showGenres = false,
                empty = "Henüz favorin yok. Bir radyonun kalbine dokun, burada parlasın.")
            Page.LIST -> listPage(listTitle, "${listItems.size} radyo", listItems, showGenres = false)
        }
    }

    private fun scrollPage(build: () -> Unit) {
        listPage.visibility = View.GONE; scroll.visibility = View.VISIBLE
        scrollContent.removeAllViews(); build()
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

        val songs = SongHistory.all(this).take(4)
        if (songs.isNotEmpty()) {
            c.addView(ui.sectionHeader("Az önce çalan şarkılar", "Tümü") { showPage(Page.SONGS) })
            songs.forEach { c.addView(songRow(it)) }
        }

        val favs = stations.filter { isFavorite(it) }
        c.addView(ui.sectionHeader("Favorilerin", if (favs.size > 6) "Tümü" else null) { showPage(Page.FAVORITES) })
        if (favs.isEmpty()) c.addView(ui.empty("Sevdiğin radyoların kalbine dokun, buraya gelsinler.")) else c.addView(cardRow(favs.take(12)))

        c.addView(ui.sectionHeader("Türler"))
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
        val history = prefs.getString("history", "").orEmpty().split("|").toSet()
        val fresh = stations.filter { it.resolvedUrl !in history }.shuffled().take(10)
        c.addView(ui.sectionHeader("Daha önce dinlemediklerin"))
        if (fresh.isEmpty()) c.addView(ui.empty("Hepsini denemişsin! O zaman Keyfime Bırak'a teslim ol.")) else c.addView(cardRow(fresh))
        val songs = SongHistory.all(this)
        c.addView(ui.sectionHeader("Şarkı geçmişin", if (songs.isNotEmpty()) "Aç" else null) { showPage(Page.SONGS) })
        if (songs.isEmpty()) c.addView(ui.empty("Radyoda çalan şarkılar burada birikecek."))
        else songs.take(3).forEach { c.addView(songRow(it)) }
    }

    private fun buildSongs() {
        val c = scrollContent
        val songs = SongHistory.all(this)
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(20), ui.dp(8), ui.dp(12), 0) }
        head.addView(ui.text("Şarkı geçmişi", 28f, Neon.TEXT, true), LinearLayout.LayoutParams(0, -2, 1f))
        if (songs.isNotEmpty()) head.addView(ui.iconButton(R.drawable.ic_trash, "Geçmişi temizle", 44, 20) {
            AlertDialog.Builder(this).setMessage("Şarkı geçmişi silinsin mi?")
                .setPositiveButton("Sil") { _, _ -> SongHistory.clear(this); showPage(Page.SONGS) }
                .setNegativeButton("Vazgeç", null).show()
        })
        c.addView(head)
        c.addView(ui.text("Radyoda duyduğun şarkılar. Birine dokun, Spotify ya da YouTube'da bul.", 13f, Neon.MUTED, lines = 2).apply { setPadding(ui.dp(20), ui.dp(6), ui.dp(20), ui.dp(12)) })
        if (songs.isEmpty()) c.addView(ui.empty("Henüz şarkı yakalamadık. Şarkı bilgisi gönderen radyolarda burası dolacak."))
        songs.forEach { c.addView(songRow(it)) }
    }

    // ---------------------------------------------------------------- building blocks

    private fun heroPickCard(): View {
        val card = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(18), ui.dp(14), ui.dp(16), ui.dp(14)); isClickable = true
            background = ui.glass(28f, Neon.withAlpha(Neon.ORANGE, 110), gradient = intArrayOf(0x66FF7A1A, 0x4DFF2E88, 0x268B5CFF))
            setOnClickListener { Ui.pop(this); openPick() }
        }
        val badge = FrameLayout(this).apply {
            background = GlassDrawable(this@MainActivity, 26f, Neon.ORANGE, 0x55FFFFFF, Neon.withAlpha(Neon.ORANGE, 160), intArrayOf(Neon.ORANGE, Neon.PINK))
            addView(ui.icon(R.drawable.ic_shuffle, Neon.TEXT, 26), FrameLayout.LayoutParams(ui.dp(26), ui.dp(26), Gravity.CENTER))
        }
        card.addView(badge, LinearLayout.LayoutParams(ui.dp(58), ui.dp(58)).apply { rightMargin = ui.dp(16) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.text("Keyfime bırak", 20f, Neon.TEXT, true))
        texts.addView(ui.text(pickSubtitle(), 13f, 0xFFFFE2CC.toInt(), lines = 2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(4) })
        card.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(ui.icon(R.drawable.ic_chevron_down, Neon.TEXT, 26).apply { rotation = -90f })
        return card
    }

    private fun moodChips() = listOf(
        R.drawable.ic_moon to "Sakin", R.drawable.ic_heart to "Dertli", R.drawable.ic_bolt to "Enerjik",
        R.drawable.ic_car to "Yoldayım", R.drawable.ic_coffee to "Kafamı dinliyorum"
    ).map { (icon, mood) -> ui.chip(mood, icon) { showMood(mood) } }

    private fun genreChips(): List<View> = stations.groupingBy { it.genre.ifBlank { "Radyo" } }.eachCount().entries
        .sortedByDescending { it.value }.map { (genre, count) -> ui.chip("$genre  $count") { genreFilter = genre; showPage(Page.RADIOS) } }

    private fun genreGrid(): View {
        val grid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(12), 0, ui.dp(12), 0) }
        val genres = stations.groupingBy { it.genre.ifBlank { "Radyo" } }.eachCount().entries.sortedByDescending { it.value }.take(8)
        genres.chunked(2).forEach { pair ->
            val row = LinearLayout(this)
            pair.forEach { (genre, count) ->
                val accent = StationArtworkView.accentFor(genre)
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.BOTTOM; setPadding(ui.dp(16), ui.dp(12), ui.dp(12), ui.dp(14)); isClickable = true
                    background = ui.glass(22f, Neon.withAlpha(accent, 70), gradient = intArrayOf(Neon.withAlpha(accent, 140), Neon.withAlpha(accent, 40), 0x0DFFFFFF))
                    setOnClickListener { Ui.pop(this); genreFilter = genre; showPage(Page.RADIOS) }
                }
                tile.addView(ui.text(genre, 17f, Neon.TEXT, true))
                tile.addView(ui.text("$count radyo", 12f, 0xCCFFFFFF.toInt()))
                row.addView(tile, LinearLayout.LayoutParams(0, ui.dp(96), 1f).apply { setMargins(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4)) })
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
            val accent = StationArtworkView.accentFor(st.genre)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.dp(12), ui.dp(14), ui.dp(12), ui.dp(12)); isClickable = true
                background = if (active) ui.glass(24f, Neon.withAlpha(accent, 150), gradient = intArrayOf(Neon.withAlpha(accent, 70), 0x26FF2E88)) else ui.glass(24f)
                setOnClickListener { Ui.pop(this); play(st) }
                setOnLongClickListener { toggleFavorite(st); true }
            }
            val art = StationArtworkView(this).apply { bind(st.name, st.genre, st.logoUrl); setPlaying(active) }
            card.addView(art, LinearLayout.LayoutParams(ui.dp(108), ui.dp(108)).apply { bottomMargin = ui.dp(10) })
            card.addView(ui.text(st.name, 13.5f, Neon.TEXT, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
            card.addView(ui.text(st.genre.ifBlank { "Radyo" }, 11.5f, Neon.MUTED).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(3) })
            row.addView(card, LinearLayout.LayoutParams(ui.dp(146), ui.dp(186)).apply { setMargins(ui.dp(5), ui.dp(4), ui.dp(5), ui.dp(4)) })
        }
        return android.widget.HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; overScrollMode = View.OVER_SCROLL_NEVER; addView(row)
        }
    }

    private fun songRow(e: SongHistory.Entry): View {
        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10)); background = ui.glass(18f); isClickable = true
            setOnClickListener { Ui.pop(this); songActions(e.title, e.artist) }
        }
        val art = StationArtworkView(this).apply { bind(e.station, "", e.logo) }
        row.addView(art, LinearLayout.LayoutParams(ui.dp(46), ui.dp(46)).apply { rightMargin = ui.dp(12) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(ui.text(e.title, 14.5f, Neon.TEXT, true))
        texts.addView(ui.text(listOf(e.artist, e.station, FullPlayerView.timeAgo(e.time)).filter { it.isNotBlank() }.joinToString(" • "), 12f, Neon.MUTED))
        row.addView(texts, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(ui.icon(R.drawable.ic_music_search, Neon.ORANGE, 20))
        return FrameLayout(this).apply {
            setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(4))
            addView(row, FrameLayout.LayoutParams(-1, -2))
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
            onFavorite = { nowPlaying().station?.let { toggleFavorite(it) } },
            onSleep = { chooseSleepTimer() },
            onSearch = { platform -> val np = nowPlaying(); searchSong(platform, np.song ?: np.station?.name.orEmpty(), np.artist) },
            onShare = { shareNowPlaying() }
        ).also { padForBars(it); it.show(root); it.bind(state, SongHistory.all(this)) }
    }

    private fun closeFullPlayer() { fullPlayer?.hide { fullPlayer = null } }

    private fun goBack() {
        when {
            pickSheet != null -> pickSheet?.close()
            fullPlayer != null -> closeFullPlayer()
            search.visibility == View.VISIBLE -> toggleSearch()
            page == Page.SONGS -> showPage(Page.DISCOVER)
            page != Page.HOME -> { genreFilter = null; showPage(Page.HOME) }
            else -> showExitDialog()
        }
    }

    private fun songActions(title: String, artist: String) {
        val query = listOf(artist, title).filter { it.isNotBlank() }.joinToString(" ")
        AlertDialog.Builder(this).setTitle(title)
            .setItems(arrayOf("Spotify'da ara", "YouTube'da ara", "Kopyala")) { _, which ->
                when (which) {
                    0 -> searchSong("spotify", title, artist)
                    1 -> searchSong("youtube", title, artist)
                    else -> {
                        getSystemService(android.content.ClipboardManager::class.java)
                            .setPrimaryClip(android.content.ClipData.newPlainText("şarkı", query))
                        toast("Kopyalandı")
                    }
                }
            }.show()
    }

    private fun searchSong(platform: String, title: String, artist: String?) {
        val query = listOf(artist?.takeIf { it != StationMedia.LIVE }.orEmpty(), title).filter { it.isNotBlank() }.joinToString(" ")
        val encoded = Uri.encode(query)
        val intents = if (platform == "spotify") listOf(Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:$encoded")), Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com/search/$encoded")))
        else listOf(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=$encoded")))
        for (intent in intents) {
            try { startActivity(intent); return } catch (_: ActivityNotFoundException) { }
        }
        toast("Açacak uygulama bulunamadı.")
    }

    private fun shareNowPlaying() {
        val np = nowPlaying(); val st = np.station ?: return
        val text = if (!np.song.isNullOrBlank()) "Keyfe Keder Radyo'da ${st.name} dinliyorum: ${listOfNotNull(np.artist?.takeIf { it != StationMedia.LIVE }, np.song).joinToString(" – ")}"
        else "Keyfe Keder Radyo'da ${st.name} dinliyorum."
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Paylaş"))
    }

    private fun chooseSleepTimer() {
        val options = arrayOf("Kapalı", "15 dakika", "30 dakika", "45 dakika", "60 dakika", "90 dakika")
        val minutes = longArrayOf(0, 15, 30, 45, 60, 90)
        AlertDialog.Builder(this).setTitle("Uyku zamanlayıcısı").setItems(options) { _, which ->
            val m = minutes[which]
            prefs.edit().putLong("sleep_until", if (m == 0L) 0L else System.currentTimeMillis() + m * 60_000L).apply()
            toast(if (m == 0L) "Uyku zamanlayıcısı kapatıldı." else "$m dakika sonra yayını durduracağım.")
            refreshPlayerUi()
        }.show()
    }

    private fun sleepLabel(): String? {
        val until = prefs.getLong("sleep_until", 0L)
        if (until <= System.currentTimeMillis()) return null
        return "${((until - System.currentTimeMillis()) / 60_000L).coerceAtLeast(1)} dk"
    }

    private fun showSettings() {
        val items = arrayOf(
            "Uyku zamanlayıcısı: ${sleepLabel() ?: "Kapalı"}",
            "Şarkı geçmişini aç",
            "Şarkı geçmişini temizle",
            "Radyo listesini yenile",
            "Hakkında (sürüm ${BuildConfig.VERSION_NAME})"
        )
        AlertDialog.Builder(this).setTitle("Ayarlar").setItems(items) { _, which ->
            when (which) {
                0 -> chooseSleepTimer()
                1 -> showPage(Page.SONGS)
                2 -> { SongHistory.clear(this); toast("Şarkı geçmişi temizlendi."); if (page == Page.HOME || page == Page.SONGS) showPage(page) }
                3 -> { toast("Liste yenileniyor…"); loadStations() }
                else -> AlertDialog.Builder(this).setTitle("Keyfe Keder Radyo")
                    .setMessage("Sürüm ${BuildConfig.VERSION_NAME}\n\n${stations.size} canlı radyo. Favorilerin ve geçmişin yalnızca bu cihazda saklanır.")
                    .setPositiveButton("Tamam", null).show()
            }
        }.show()
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
                        if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) && page == Page.HOME) showPage(Page.HOME)
                    }
                    override fun onPlayerError(error: PlaybackException) { playerError = true; refreshPlayerUi() }
                })
                refreshPlayerUi()
            }, ContextCompat.getMainExecutor(this))
        }
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

    private fun refreshPlayerUi() {
        if (isFinishing || isDestroyed) return
        val np = nowPlaying()
        val st = np.station
        val accent = st?.let { StationArtworkView.accentFor(it.genre) } ?: Neon.ORANGE
        ambient.setAccent(accent)
        if (st == null) {
            miniArt.bind("RADYO", ""); miniArt.setPlaying(false)
            miniTitle.text = "Bir radyo seç"; miniSub.text = "Keyfime Bırak'ı dene"
            miniSpectrum.setPlaying(false); miniSpectrum.visibility = View.INVISIBLE
        } else {
            miniArt.bind(st.name, st.genre, st.logoUrl); miniArt.setPlaying(np.playing)
            miniTitle.text = np.song ?: st.name
            miniSub.text = when {
                np.error -> "Yayına ulaşılamadı, yeniden deneniyor…"
                np.buffering -> "Bağlanıyor…"
                np.song != null -> listOfNotNull(np.artist?.takeIf { it != StationMedia.LIVE }, st.name).joinToString(" • ")
                np.playing -> "● CANLI • ${st.name}"
                else -> "Duraklatıldı • ${st.name}"
            }
            miniSub.setTextColor(if (np.playing && np.song == null) accent else Neon.MUTED)
            miniSpectrum.setAccent(accent); miniSpectrum.setPlaying(np.playing)
            miniSpectrum.visibility = if (np.playing || np.buffering) View.VISIBLE else View.INVISIBLE
        }
        miniPlayIcon.setImageResource(if (np.playing || np.buffering) R.drawable.ic_pause else R.drawable.ic_play)
        miniPlay.background = GlassDrawable(this, 25f, accent, 0x55FFFFFF, Neon.withAlpha(accent, if (np.playing) 170 else 60), intArrayOf(accent, Neon.mix(accent, Neon.PINK, .7f)))
        mini.background = ui.glass(24f, if (np.playing) Neon.withAlpha(accent, 80) else 0, fill = 0x2EFFFFFF)
        fullPlayer?.bind(np, SongHistory.all(this))
    }

    // ---------------------------------------------------------------- data

    private fun loadStations() {
        val repo = StationRepository(applicationContext)
        executor.execute {
            // Show the cached/bundled list immediately, then swap in the fresh one if it downloads
            runCatching { repo.loadLocal() }.getOrNull()?.let { local -> runOnUiThread { applyStations(local) } }
            runCatching { repo.refresh() }.getOrNull()?.let { fresh -> runOnUiThread { applyStations(fresh) } }
        }
    }

    private fun applyStations(loaded: List<Station>) {
        if (isFinishing || isDestroyed || loaded.isEmpty() || loaded == stations) return
        stations = loaded
        if (page == Page.LIST && search.text.isNotBlank()) searchFor(search.text.toString()) else showPage(page)
        refreshPlayerUi()
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

    private fun toggleFavorite(station: Station) {
        val now = !isFavorite(station)
        prefs.edit().putBoolean(station.resolvedUrl, now).apply()
        toast(if (now) "${station.name} favorilere eklendi" else "${station.name} favorilerden çıkarıldı")
        when (page) {
            Page.FAVORITES, Page.HOME -> showPage(page)
            else -> adapter.refresh()
        }
        refreshPlayerUi()
    }

    // ---------------------------------------------------------------- misc

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
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
