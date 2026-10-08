package com.keyfekederradyo.android

import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Metadata
import androidx.media3.extractor.metadata.icy.IcyInfo
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {
    private val bg = Color.rgb(13,13,14)
    private val surface = Color.rgb(27,27,29)
    private val surface2 = Color.rgb(35,35,38)
    private val orange = Color.rgb(255,122,0)
    private val white = Color.rgb(245,245,247)
    private val muted = Color.rgb(150,150,155)
    private val prefs by lazy { getSharedPreferences("radio", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val taglines = listOf("Bugün ne açsak? ","Keyfin ne isterse, frekans orada.","Biraz müzik, biraz keyif.","Kafana göre bir radyo bulalım.","Bir Frekans, Bin Keyif.")
    private var taglineIndex = 0
    private var stations = emptyList<Station>()
    private var currentIndex = -1
    private var selectedNav = 0
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var nowPlaying = ""
    private var fullPlayerDialog: android.app.Dialog? = null
    private var fullPlayerRefresh: (() -> Unit)? = null

    private lateinit var adapter: StationAdapter
    private lateinit var homeScroll: ScrollView
    private lateinit var homeContainer: LinearLayout
    private lateinit var stationList: RecyclerView
    private lateinit var search: EditText
    private lateinit var title: TextView
    private lateinit var status: TextView
    private lateinit var liveBadge: TextView
    private lateinit var tagline: TextView
    private lateinit var play: ImageButton
    private lateinit var spectrum: AudioSpectrumView
    private lateinit var miniLogo: StationArtworkView
    private val navCards = mutableListOf<View>()
    private val navIcons = mutableListOf<ImageView>()
    private val navLabels = mutableListOf<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(bg))
        runCatching { android.net.http.HttpResponseCache.install(java.io.File(cacheDir, "http"), 16L * 1024 * 1024) }
        val content = buildUi()
        // Android 15+ draws edge-to-edge: keep the UI clear of the status and navigation bars
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(content) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(content)
        handler.postDelayed(taglineRunnable, 1800L)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (fullPlayerDialog?.isShowing == true) {
                    fullPlayerDialog?.dismiss()
                } else if (search.visibility == View.VISIBLE) {
                    search.visibility = View.GONE
                    search.clearFocus()
                } else if (selectedNav != 0) {
                    handleNav(0)
                } else {
                    showExitDialog()
                }
            }
        })
        connectPlayer()
        loadStations()
    }

    private val taglineRunnable = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            tagline.animate().alpha(0f).setDuration(160).withEndAction {
                if (isFinishing || isDestroyed) return@withEndAction
                taglineIndex = (taglineIndex + 1) % taglines.size
                tagline.text = taglines[taglineIndex]
                tagline.animate().alpha(1f).setDuration(240).start()
            }.start()
            handler.postDelayed(this, 3600L)
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d(10), d(7), d(10), d(6))
        }
        val brandBox = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, d(62), 1f)
        }
        val brand = ImageView(this).apply {
            setImageResource(R.drawable.keyfe_keder_brand)
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = rounded(Color.rgb(18,18,19), 12)
            contentDescription = "Keyfe Keder Radyo"
        }
        brandBox.addView(brand, LinearLayout.LayoutParams(d(42), d(42)).apply { rightMargin = d(10) })
        val brandText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL }
        val brandTitle = TextView(this).apply {
            text = "KEYFE KEDER RADYO"
            textSize = 14f
            setTextColor(white)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = 0.01f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        tagline = TextView(this).apply { text = taglines[0]; textSize = 10.5f; setTextColor(orange); alpha = 0.9f }
        brandText.addView(brandTitle)
        brandText.addView(tagline)
        brandBox.addView(brandText, LinearLayout.LayoutParams(0, -2, 1f))
        val searchButton = iconButton(R.drawable.ic_search)
        liveBadge = TextView(this).apply {
            text = "RADYO"; textSize = 10f; setTextColor(muted); gravity = Gravity.CENTER
            background = rounded(Color.rgb(28,24,21), 14)
        }
        val settingsButton = iconButton(R.drawable.ic_settings).apply { contentDescription = "Ayarlar" }
        settingsButton.setOnClickListener { tap(it); showSettings() }
        top.addView(brandBox)
        top.addView(searchButton, LinearLayout.LayoutParams(d(48), d(48)))
        top.addView(settingsButton, LinearLayout.LayoutParams(d(48), d(48)))
        top.addView(liveBadge, LinearLayout.LayoutParams(d(64), d(30)).apply { leftMargin = d(2) })
        root.addView(top)

        search = EditText(this).apply {
            hint = "Radyo ara..."; setTextColor(white); setHintTextColor(muted); setSingleLine(true)
            setPadding(d(16), 0, d(16), 0); background = rounded(surface, 18); visibility = View.GONE
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter(s?.toString().orEmpty()) }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        root.addView(search, LinearLayout.LayoutParams(-1, d(50)).apply { setMargins(d(10), 0, d(10), d(8)) })
        searchButton.setOnClickListener { tap(it); search.visibility = if (search.visibility == View.VISIBLE) View.GONE else View.VISIBLE; if (search.visibility == View.VISIBLE) search.requestFocus() }

        homeScroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; isFillViewport = true; setPadding(d(10),0,d(10),d(8)) }
        homeContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0,d(4),0,d(18)) }
        homeScroll.addView(homeContainer, ViewGroup.LayoutParams(-1,-1))
        root.addView(homeScroll, LinearLayout.LayoutParams(-1,0,1f))

        stationList = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@MainActivity, 2)
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(d(5),0,d(5),d(8)); clipToPadding = false; visibility = View.GONE
        }
        adapter = StationAdapter({ play(it) }, { isFavorite(it) }, { toggleFavorite(it) })
        stationList.adapter = adapter
        root.addView(stationList, LinearLayout.LayoutParams(-1,0,1f))
        root.addView(buildMiniPlayer(), LinearLayout.LayoutParams(-1,d(76)).apply { setMargins(d(8),d(4),d(8),d(6)) })
        root.addView(buildBottomNav(), LinearLayout.LayoutParams(-1,d(68)))
        buildHome()
        return root
    }

    private fun buildBottomNav(): View {
        val nav = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(d(6),d(5),d(6),d(6)); setBackgroundColor(surface) }
        val items = listOf(R.drawable.ic_home to "Ana Sayfa",R.drawable.ic_radio to "Radyolar",R.drawable.ic_explore to "Keşfet",R.drawable.ic_heart to "Favoriler")
        items.forEachIndexed { index, pair ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0,-1,1f).apply { leftMargin=d(2); rightMargin=d(2) }
                background = rounded(if(index==0) Color.rgb(44,31,23) else Color.TRANSPARENT, 16)
                setOnClickListener { tap(this); handleNav(index) }
            }
            val icon = ImageView(this).apply { setImageResource(pair.first); setColorFilter(if(index==0) orange else muted); layoutParams=LinearLayout.LayoutParams(d(22),d(24)) }
            val label = TextView(this).apply { text=pair.second; textSize=10.5f; setTextColor(if(index==0)orange else muted); gravity=Gravity.CENTER }
            card.addView(icon); card.addView(label); nav.addView(card); navCards.add(card); navIcons.add(icon); navLabels.add(label)
        }
        return nav
    }

    private fun selectNav(index:Int) {
        selectedNav = index
        navCards.forEachIndexed { i, card ->
            val active = i == index
            card.background = rounded(if(active) Color.rgb(44,31,23) else Color.TRANSPARENT,16)
            navIcons[i].setColorFilter(if(active) orange else muted)
            navLabels[i].setTextColor(if(active) orange else muted)
            card.alpha = if(active) 1f else 0.86f
        }
    }

    private fun handleNav(index:Int) {
        selectNav(index); search.visibility=View.GONE; search.clearFocus()
        when(index) {
            0 -> { stationList.visibility=View.GONE; homeScroll.visibility=View.VISIBLE; buildHome(); homeScroll.scrollTo(0,0) }
            1 -> showRadios(stations)
            2 -> { stationList.visibility=View.GONE; homeScroll.visibility=View.VISIBLE; buildDiscover(); homeScroll.scrollTo(0,0) }
            3 -> showRadios(stations.filter { isFavorite(it) })
        }
    }

    private fun showRadios(list:List<Station>) { homeScroll.visibility=View.GONE; stationList.visibility=View.VISIBLE; adapter.submitList(list); stationList.scrollToPosition(0) }

    private fun buildHome() {
        homeContainer.removeAllViews()
        homeContainer.addView(TextView(this).apply{text="Selam ";textSize=13f;setTextColor(orange);setPadding(d(4),d(10),d(4),0)})
        homeContainer.addView(TextView(this).apply{text="Bugün hangi frekanstasın?";textSize=27f;setTextColor(white);setTypeface(typeface,android.graphics.Typeface.BOLD);setPadding(d(4),d(2),d(4),0)})
        homeContainer.addView(TextView(this).apply{text="Canın ne istiyorsa söyle, gerisini ben hallederim. ";textSize=13f;setTextColor(muted);setPadding(d(4),d(4),d(4),d(14))})
        homeContainer.addView(buildPickerCard())
        homeContainer.addView(TextView(this).apply{text="Hemen bir mod seç";textSize=18f;setTextColor(white);setTypeface(typeface,android.graphics.Typeface.BOLD);setPadding(d(4),d(18),d(4),d(8))})
        val chips=android.widget.HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        val moodItems=listOf(R.drawable.ic_moon to "Sakin",R.drawable.ic_heart to "Dertli",R.drawable.ic_bolt to "Enerjik",R.drawable.ic_car to "Yoldayım",R.drawable.ic_coffee to "Kafamı dinliyorum")
        moodItems.forEach{(iconRes,label)->
            val chip=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER;setPadding(d(13),0,d(15),0);background=rounded(surface2,20);isClickable=true;setOnClickListener{tap(this);showMood(label)}}
            chip.addView(ImageView(this).apply{setImageResource(iconRes);setColorFilter(white);layoutParams=LinearLayout.LayoutParams(d(18),d(20)).apply{rightMargin=d(7)}})
            chip.addView(TextView(this).apply{text=label;textSize=12f;setTextColor(white);gravity=Gravity.CENTER})
            row.addView(chip,LinearLayout.LayoutParams(-2,d(42)).apply{rightMargin=d(8)})
        }
        chips.addView(row,ViewGroup.LayoutParams(-2,d(46)));homeContainer.addView(chips,LinearLayout.LayoutParams(-1,d(52)))
        addSection("Son dinlediklerin",historyStations(),"Henüz birlikte bir radyo dinlemedik.")
        addSection("Kalbini bıraktıkların",stations.filter{isFavorite(it)}.take(10),"Buraya sevdiğin radyoları atalım. Kalbe dokun yeter.")
        addSection("Bizim seçimlerimiz",stations.filter{it.genre.isNotBlank()}.take(10),"Birazdan sana güzel frekanslar çıkarırım.")
    }
    private fun buildPickerCard():View {
        val card=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(d(14),d(12),d(10),d(12)); background=GradientDrawable().apply { setColor(Color.rgb(39,27,20)); cornerRadius=d(24).toFloat(); setStroke(d(1),Color.rgb(105,58,28)) }; setOnClickListener { tap(this); pickForMe() } }
        card.addView(ImageView(this).apply { setImageResource(R.drawable.ic_shuffle); setColorFilter(orange); scaleType=ImageView.ScaleType.CENTER_INSIDE; contentDescription="Rastgele radyo" },LinearLayout.LayoutParams(d(58),d(62)))
        val info=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER_VERTICAL; layoutParams=LinearLayout.LayoutParams(0,-2,1f) }
        info.addView(TextView(this).apply { text="KEYFİME BIRAK"; textSize=16f; setTextColor(white); setTypeface(typeface,android.graphics.Typeface.BOLD); letterSpacing=0.04f })
        info.addView(TextView(this).apply { text="Ben bir frekans bulayım, kararı sen ver."; textSize=11f; setTextColor(Color.rgb(204,166,136)) })
        card.addView(info); card.addView(TextView(this).apply { text="›"; textSize=30f; setTextColor(orange); gravity=Gravity.CENTER },LinearLayout.LayoutParams(d(30),d(56))); return card
    }

    private fun showMood(mood:String) {
        if(stations.isEmpty()){android.widget.Toast.makeText(this,"Radyolar henüz yükleniyor.",android.widget.Toast.LENGTH_SHORT).show();return}
        val list=RadioMoodMatcher.rank(stations,mood)
        if(list.isEmpty()) android.widget.Toast.makeText(this,"Bu moda uygun radyo bulamadım. Keyfime Bırak'ı dene.",android.widget.Toast.LENGTH_SHORT).show() else {showRadios(list);selectNav(1)}
    }

    private fun pickForMe() {
        if(isFinishing||isDestroyed)return
        if(stations.isEmpty()){android.widget.Toast.makeText(this,"Radyolar henüz yükleniyor.",android.widget.Toast.LENGTH_SHORT).show();return}
        val history=prefs.getString("history","").orEmpty().split("|").filter{it.isNotBlank()}.toSet(); val recent=history.take(4).toSet()
        val pool=stations.filter{it.resolvedUrl !in recent}.ifEmpty{stations}; if(pool.isEmpty())return
        val chosen=pool.filter{it.resolvedUrl!=stations.getOrNull(currentIndex)?.resolvedUrl}.randomOrNull() ?: pool.random()
        showPickResult(chosen,stations.any{isFavorite(it)})
    }

    private fun showPickResult(station:Station,hasFavorites:Boolean) {
        if(isFinishing||isDestroyed)return

        val meta=listOf(station.genre,station.country).filter{it.isNotBlank()}.joinToString(" • ")
        val reason=if(isFavorite(station))"Favorilerinden sana bir seçim." else if(hasFavorites)"Favorilerine farklı bir alternatif." else "Daha önce sıkmadığın bir frekans."

        val dialog=android.app.Dialog(this)
        dialog.window?.setDimAmount(.78f)

        val root=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(d(18),d(12),d(18),d(18))
            background=GradientDrawable().apply{
                setColor(Color.rgb(20,20,22))
                cornerRadii=floatArrayOf(
                    d(28).toFloat(),d(28).toFloat(),
                    d(28).toFloat(),d(28).toFloat(),
                    d(0).toFloat(),d(0).toFloat(),
                    d(0).toFloat(),d(0).toFloat()
                )
                setStroke(d(1),Color.rgb(66,46,34))
            }
        }

        val handle=View(this).apply{background=rounded(Color.rgb(88,78,72),3)}
        root.addView(handle,LinearLayout.LayoutParams(d(48),d(5)).apply{gravity=Gravity.CENTER;bottomMargin=d(14)})

        val title=TextView(this).apply{
            text="BUGÜNÜN FREKANSI"
            textSize=11f
            setTextColor(orange)
            typeface=android.graphics.Typeface.DEFAULT_BOLD
            letterSpacing=.10f
            gravity=Gravity.CENTER
        }
        root.addView(title,LinearLayout.LayoutParams(-1,d(30)))

        val art=StationArtworkView(this).apply{
            background=rounded(Color.rgb(14,14,16),24)
            clipToOutline=true
            bind(station.name,station.genre,station.logoUrl)
        }
        root.addView(art,LinearLayout.LayoutParams(d(190),d(150)).apply{
            gravity=Gravity.CENTER
            bottomMargin=d(12)
        })

        root.addView(TextView(this).apply{
            text=station.name
            textSize=22f
            setTextColor(white)
            gravity=Gravity.CENTER
            setTypeface(typeface,android.graphics.Typeface.BOLD)
            maxLines=2
        },LinearLayout.LayoutParams(-1,d(54)))

        root.addView(TextView(this).apply{
            text=meta.ifBlank{"CANLI RADYO"}
            textSize=11f
            setTextColor(muted)
            gravity=Gravity.CENTER
        },LinearLayout.LayoutParams(-1,d(28)))

        val reasonBox=LinearLayout(this).apply{
            orientation=LinearLayout.HORIZONTAL
            gravity=Gravity.CENTER_VERTICAL
            setPadding(d(14),d(10),d(14),d(10))
            background=rounded(Color.rgb(31,25,22),18)
        }
        reasonBox.addView(TextView(this).apply{
            text="Seçim"
            textSize=17f
            setTextColor(orange)
            gravity=Gravity.CENTER
        },LinearLayout.LayoutParams(d(30),d(38)))
        reasonBox.addView(TextView(this).apply{
            text=reason
            textSize=11f
            setTextColor(Color.rgb(214,203,196))
            maxLines=2
        },LinearLayout.LayoutParams(0,d(44),1f))
        root.addView(reasonBox,LinearLayout.LayoutParams(-1,d(64)).apply{bottomMargin=d(12)})

        val listen=TextView(this).apply{
            text="▶   ŞİMDİ DİNLE"
            textSize=13f
            setTextColor(Color.WHITE)
            gravity=Gravity.CENTER
            typeface=android.graphics.Typeface.DEFAULT_BOLD
            background=GradientDrawable().apply{setColor(orange);cornerRadius=d(20).toFloat()}
            isClickable=true
            setOnClickListener{tap(this);dialog.dismiss();play(station)}
        }
        root.addView(listen,LinearLayout.LayoutParams(-1,d(52)).apply{bottomMargin=d(8)})

        val secondary=LinearLayout(this).apply{gravity=Gravity.CENTER}
        val another=TextView(this).apply{
            text="Rastgele  BAŞKA BİR TANE"
            textSize=11f
            setTextColor(muted)
            gravity=Gravity.CENTER
            setPadding(d(16),0,d(16),0)
            isClickable=true
            setOnClickListener{
                tap(this)
                dialog.dismiss()
                handler.postDelayed({if(!isFinishing&&!isDestroyed)pickForMe()},180L)
            }
        }
        val close=TextView(this).apply{
            text="KAPAT"
            textSize=11f
            setTextColor(muted)
            gravity=Gravity.CENTER
            setPadding(d(16),0,d(16),0)
            isClickable=true
            setOnClickListener{tap(this);dialog.dismiss()}
        }
        secondary.addView(another,LinearLayout.LayoutParams(0,d(44),1f))
        secondary.addView(close,LinearLayout.LayoutParams(0,d(44),1f))
        root.addView(secondary,LinearLayout.LayoutParams(-1,d(44)))

        dialog.setContentView(root)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
        dialog.window?.setLayout(-1,(resources.displayMetrics.heightPixels*.78f).toInt())
    }

    private fun buildDiscover() {
        homeContainer.removeAllViews()
        homeContainer.addView(TextView(this).apply { text="Biraz kurcalayalım mı? Akıcı"; textSize=25f; setTextColor(white); setTypeface(typeface,android.graphics.Typeface.BOLD); setPadding(d(4),d(12),d(4),0) })
        homeContainer.addView(TextView(this).apply { text="Burada yeni frekanslar buluyoruz. Hep aynı radyoda takılmak yok. "; textSize=13f; setTextColor(muted); setPadding(d(4),d(4),d(4),d(16)) })
        homeContainer.addView(TextView(this).apply { text="Ruh haline göre"; textSize=18f; setTextColor(white); setTypeface(typeface,android.graphics.Typeface.BOLD); setPadding(d(4),d(8),d(4),d(8)) })
        val sc=android.widget.HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}
        val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        listOf("Sakin","Dertli","Enerjik","Gece","Yolculuk").forEach{label->
            val chip=TextView(this).apply{text=label;textSize=12f;setTextColor(white);gravity=Gravity.CENTER;setPadding(d(15),0,d(15),0);background=rounded(surface2,20);setOnClickListener{tap(this);discoverFilter(label)}}
            row.addView(chip,LinearLayout.LayoutParams(-2,d(42)).apply{rightMargin=d(8)})
        }
        sc.addView(row,ViewGroup.LayoutParams(-2,d(46)));homeContainer.addView(sc,LinearLayout.LayoutParams(-1,d(52)))
        val history=prefs.getString("history","").orEmpty().split("|").filter{it.isNotBlank()}.toSet()
        addSection("Daha önce dinlemediklerin",stations.filter{it.resolvedUrl !in history}.shuffled().take(10),"Hepsini denemişsin. O zaman Keyfime Bırak'a teslim ol. ")
        addSection("Rastgele Bugün ben seçtim",stations.shuffled().take(10),"Radyoları çekiyoruz, az sonra burada olur. ")
    }

    private fun discoverFilter(filter:String) {
        val rx=when(filter){
            "Sakin"->Regex("chill|lounge|jazz|classical|easy",RegexOption.IGNORE_CASE)
            "Dertli"->Regex("arabesk|fantazi|damar|slow",RegexOption.IGNORE_CASE)
            "Enerjik"->Regex("pop|hit|top|dance",RegexOption.IGNORE_CASE)
            "Gece"->Regex("night|gece|chill|lounge|slow",RegexOption.IGNORE_CASE)
            else->Regex("road|drive|80|90",RegexOption.IGNORE_CASE)
        }
        val list=stations.filter{"${it.genre} ${it.name}".contains(rx)}
        if(list.isEmpty()) android.widget.Toast.makeText(this,"Bu köşede şu an pek radyo yok. Başka birine bakalım. ",android.widget.Toast.LENGTH_SHORT).show() else {showRadios(list);selectNav(1)}
    }
    private fun addSection(name:String,list:List<Station>,empty:String){
        homeContainer.addView(TextView(this).apply{text=name;textSize=18f;setTextColor(white);setTypeface(typeface,android.graphics.Typeface.BOLD);setPadding(d(4),d(18),d(4),d(8))})
        if(list.isEmpty()){homeContainer.addView(TextView(this).apply{text=empty;textSize=12f;setTextColor(muted);setPadding(d(8),0,d(8),0)},LinearLayout.LayoutParams(-1,d(42)));return}
        val scroll=android.widget.HorizontalScrollView(this).apply{isHorizontalScrollBarEnabled=false;overScrollMode=View.OVER_SCROLL_NEVER}; val row=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL}
        list.forEach{st->row.addView(homeCard(st),LinearLayout.LayoutParams(d(154),d(176)).apply{rightMargin=d(10)})};scroll.addView(row,ViewGroup.LayoutParams(-2,d(180)));homeContainer.addView(scroll,LinearLayout.LayoutParams(-1,d(184)))
    }

    private fun homeCard(station:Station):View {
        val card=FrameLayout(this).apply {
            background=GradientDrawable().apply{
                setColor(surface2)
                cornerRadius=d(24).toFloat()
                setStroke(d(1),Color.rgb(52,45,41))
            }
            isClickable=true
            isFocusable=true
            elevation=d(4).toFloat()
            setOnClickListener{tap(this);play(station)}
        }

        val glow=View(this).apply{
            background=GradientDrawable().apply{
                setColor(Color.rgb(49,28,17))
                cornerRadius=d(28).toFloat()
                setStroke(d(1),Color.rgb(103,61,31))
            }
            alpha=0.72f
        }
        card.addView(glow,FrameLayout.LayoutParams(-1,-1).apply{
            leftMargin=d(4);rightMargin=d(4);topMargin=d(4);bottomMargin=d(-1)
        })
        val artwork=StationArtworkView(this).apply{
            bind(station.name,station.genre,station.logoUrl)
            setPlaying(station.resolvedUrl==controller?.currentMediaItem?.mediaId&&controller?.isPlaying==true)
        }
        // Cover sits in the upper part of the card; name and genre stay readable below it
        card.addView(artwork,FrameLayout.LayoutParams(d(100),d(100)).apply{gravity=Gravity.TOP or Gravity.CENTER_HORIZONTAL;topMargin=d(12)})

        val shade=View(this).apply{
            background=android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(0x00101012,0x10101012,0xE80B0B0D.toInt())
            )
            isClickable=false
        }
        card.addView(shade,FrameLayout.LayoutParams(-1,-1))

        val top=LinearLayout(this).apply{
            gravity=Gravity.TOP or Gravity.END
            setPadding(d(10),d(9),d(10),0)
        }
        val favorite=ImageButton(this).apply{
            setImageResource(if(isFavorite(station)) R.drawable.ic_heart else R.drawable.ic_heart_outline)
            setColorFilter(if(isFavorite(station)) orange else white)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(d(7),d(7),d(7),d(7))
            contentDescription=if(isFavorite(station)) "Favorilerden çıkar" else "Favorilere ekle"
            setOnClickListener{
                tap(it)
                toggleFavorite(station)
            }
        }
        top.addView(favorite,LinearLayout.LayoutParams(d(36),d(36)))
        card.addView(top,FrameLayout.LayoutParams(-1,d(46)).apply{gravity=Gravity.TOP})

        val bottom=LinearLayout(this).apply{
            orientation=LinearLayout.VERTICAL
            setPadding(d(13),0,d(13),d(12))
        }
        val live=TextView(this).apply{
            text="●  CANLI"
            textSize=9.5f
            setTextColor(orange)
            typeface=android.graphics.Typeface.DEFAULT_BOLD
            visibility=if(station.resolvedUrl==controller?.currentMediaItem?.mediaId)View.VISIBLE else View.GONE
        }
        val name=TextView(this).apply{
            text=station.name
            textSize=15f
            setTextColor(white)
            setTypeface(typeface,android.graphics.Typeface.BOLD)
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
        }
        val meta=TextView(this).apply{
            text=listOf(station.genre,station.country).filter{it.isNotBlank()}.joinToString(" • ").ifBlank{"Canlı radyo"}
            textSize=10.5f
            setTextColor(0xD0FFFFFF.toInt())
            maxLines=1
            ellipsize=android.text.TextUtils.TruncateAt.END
        }
        bottom.addView(live,LinearLayout.LayoutParams(-1,d(18)))
        bottom.addView(name,LinearLayout.LayoutParams(-1,d(26)))
        bottom.addView(meta,LinearLayout.LayoutParams(-1,d(20)))
        card.addView(bottom,FrameLayout.LayoutParams(-1,d(70)).apply{gravity=Gravity.BOTTOM})

        return card
    }

    private fun buildMiniPlayer():View {
        val row=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(d(10),d(7),d(8),d(7));background=rounded(surface2,24);elevation=d(8).toFloat();setOnClickListener{if(currentIndex>=0&&!isFinishing&&!isDestroyed)showFullPlayer()}}
        miniLogo=StationArtworkView(this).apply{layoutParams=LinearLayout.LayoutParams(d(50),d(50)).apply{rightMargin=d(9)};bind("RADYO","")}
        val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(0,-2,1f)}
        title=TextView(this).apply{text="Bir radyo seç";textSize=14f;setTextColor(white);maxLines=1};status=TextView(this).apply{text="Hazır";textSize=11f;setTextColor(muted);maxLines=1};spectrum=AudioSpectrumView(this).apply{layoutParams=LinearLayout.LayoutParams(-1,d(16))}
        info.addView(title);info.addView(status);info.addView(spectrum)
        val prev=iconButton(R.drawable.ic_prev); play=iconButton(R.drawable.ic_play); val next=iconButton(R.drawable.ic_next)
        prev.setOnClickListener{tap(it);playRelative(-1)};play.setOnClickListener{tap(it);togglePlay()};next.setOnClickListener{tap(it);playRelative(1)}
        row.addView(miniLogo);row.addView(info);row.addView(prev);row.addView(play);row.addView(next);return row
    }

    private fun connectPlayer(){
        val token=SessionToken(this,ComponentName(this,RadioPlaybackService::class.java));controllerFuture=MediaController.Builder(this,token).buildAsync()
        controllerFuture?.addListener({
            try{
                controller=controllerFuture?.get()
                controller?.addListener(object:androidx.media3.common.Player.Listener{
                    private fun refresh(){
                        if(isFinishing||isDestroyed)return
                        val p=controller?:return;val playing=p.isPlaying
                        play.setImageResource(if(playing)R.drawable.ic_pause else R.drawable.ic_play);spectrum.setPlaying(playing);miniLogo.setPlaying(playing)
                        status.text=when{p.playbackState==androidx.media3.common.Player.STATE_BUFFERING->"Bağlanıyor...";playing->if(nowPlaying.isNotBlank())nowPlaying else "CANLI • "+title.text;p.playbackState==androidx.media3.common.Player.STATE_READY->"Durduruldu";else->"Hazır"}
                        liveBadge.text=when{playing->"● CANLI";p.playbackState==androidx.media3.common.Player.STATE_BUFFERING->"BAĞLANIYOR";else->"RADYO"};liveBadge.setTextColor(if(playing||p.playbackState==androidx.media3.common.Player.STATE_BUFFERING)orange else muted);fullPlayerRefresh?.invoke()
                    }
                    override fun onIsPlayingChanged(isPlaying:Boolean)=refresh()
                    override fun onPlaybackStateChanged(playbackState:Int)=refresh()
                    override fun onMediaItemTransition(item:MediaItem?,reason:Int){title.text=item?.mediaMetadata?.title?:"Bir radyo seç";syncCurrentStation();refresh()}
                    override fun onMetadata(metadata:Metadata){for(i in 0 until metadata.length()){(metadata.get(i)as?IcyInfo)?.title?.trim()?.takeIf{it.isNotBlank()}?.let{raw->nowPlaying=formatNowPlaying(raw);if(!isFinishing&&!isDestroyed)status.text=nowPlaying;fullPlayerRefresh?.invoke()}}}
                    override fun onPlayerError(error:androidx.media3.common.PlaybackException){nowPlaying="";if(!isFinishing&&!isDestroyed){status.text="Bu yayın açılmadı. Başka bir frekans deneyelim. ";spectrum.setPlaying(false);miniLogo.setPlaying(false);liveBadge.text="RADYO";liveBadge.setTextColor(muted);fullPlayerRefresh?.invoke()}}
                })
                syncCurrentStation()
            }catch(_:Exception){if(!isFinishing&&!isDestroyed)status.text="Müzik kutusunu açamadım. Bir daha deneyelim. "}
        },ContextCompat.getMainExecutor(this))
    }

    private fun syncCurrentStation(){
        val p=controller?:return;val item=p.currentMediaItem?:return;val index=stations.indexOfFirst{it.resolvedUrl==item.mediaId}
        if(index>=0){currentIndex=index;title.text=stations[index].name;miniLogo.bind(stations[index].name,stations[index].genre,stations[index].logoUrl);miniLogo.setPlaying(p.isPlaying);spectrum.setStationSeed(stations[index].name.hashCode())}
        play.setImageResource(if(p.isPlaying)R.drawable.ic_pause else R.drawable.ic_play);spectrum.setPlaying(p.isPlaying);fullPlayerRefresh?.invoke()
    }
    private fun togglePlay(){controller?.let{if(it.isPlaying)it.pause()else it.play()}}
    private fun formatNowPlaying(raw:String):String{val cleaned=raw.replace(Regex("\\s+")," ").trim();val parts=cleaned.split(Regex("\\s+[-–—/]\\s+|\\|"),limit=2);return if(parts.size==2)parts[0].trim()+" • "+parts[1].trim()else cleaned}
    private fun playRelative(delta:Int){if(stations.isEmpty())return;currentIndex=if(currentIndex<0)0 else(currentIndex+delta+stations.size)%stations.size;play(stations[currentIndex])}
    private fun play(station:Station){
        if(isFinishing||isDestroyed)return
        rememberStation(station);currentIndex=stations.indexOfFirst{it.resolvedUrl==station.resolvedUrl}.coerceAtLeast(0)
        val metadata=androidx.media3.common.MediaMetadata.Builder().setTitle(station.name).setArtist("Keyfe Keder Radyo").setAlbumTitle("Canlı Yayın").setArtworkUri(Uri.parse("android.resource://$packageName/drawable/station_artwork_default")).build()
        val item=MediaItem.Builder().setMediaId(station.resolvedUrl).setUri(station.resolvedUrl).setMediaMetadata(metadata).build()
        try{controller?.setMediaItem(item);controller?.prepare();controller?.play()}catch(_:Exception){status.text="Bu radyoya bağlanamadık. Başka birini deneyelim. ";return}
        title.text=station.name;nowPlaying="";status.text="Bağlanıyor...";liveBadge.text="BAĞLANIYOR";liveBadge.setTextColor(orange);miniLogo.bind(station.name,station.genre,station.logoUrl);miniLogo.setPlaying(true);spectrum.setStationSeed(station.name.hashCode());spectrum.restart();fullPlayerRefresh?.invoke()
    }
    private fun filter(query:String){val q=query.trim();val list=if(q.isBlank())stations else stations.filter{it.name.contains(q,true)||it.genre.contains(q,true)||it.country.contains(q,true)||it.language.contains(q,true)};if(selectedNav==0){showRadios(list);selectNav(1)}else adapter.submitList(list)}
    private fun loadStations(){
        val repo=StationRepository(applicationContext)
        executor.execute{
            // Show the cached/bundled list immediately, then swap in the fresh one if it downloads
            runCatching{repo.loadLocal()}.getOrNull()?.let{local->runOnUiThread{applyStations(local)}}
            runCatching{repo.refresh()}.getOrNull()?.let{fresh->runOnUiThread{applyStations(fresh)}}
        }
    }
    private fun applyStations(loaded:List<Station>){
        if(isFinishing||isDestroyed||loaded.isEmpty()||loaded==stations)return
        stations=loaded
        when(selectedNav){0->buildHome();1->if(search.text.isNullOrBlank())adapter.submitList(loaded) else filter(search.text.toString());2->buildDiscover();3->showRadios(loaded.filter{isFavorite(it)})}
        syncCurrentStation()
    }
    private fun historyStations():List<Station>{val urls=prefs.getString("history","").orEmpty().split("|").filter{it.isNotBlank()};return urls.mapNotNull{url->stations.firstOrNull{it.resolvedUrl==url}}.take(10)}
    private fun rememberStation(station:Station){val urls=prefs.getString("history","").orEmpty().split("|").filter{it.isNotBlank()&&it!=station.resolvedUrl}.toMutableList();urls.add(0,station.resolvedUrl);prefs.edit().putString("history",urls.take(12).joinToString("|")).apply()}
    private fun isFavorite(station:Station)=prefs.getBoolean(station.resolvedUrl,false)
    private fun toggleFavorite(station:Station){prefs.edit().putBoolean(station.resolvedUrl,!isFavorite(station)).apply();adapter.notifyDataSetChanged();if(selectedNav==0)buildHome();if(selectedNav==3)showRadios(stations.filter{isFavorite(it)});fullPlayerRefresh?.invoke()}

    private fun showExitDialog(){
        if(controller?.isPlaying!=true){finishAndRemoveTask();return}
        val dialog=android.app.AlertDialog.Builder(this).setTitle("Keyfe Keder Radyo").setMessage("Uygulamadan çıkmak istiyor musun?\n\nArka planda çalmaya devam edebilirim veya yayını tamamen kapatabilirim.").setPositiveButton("Arka planda çal"){_,_->finishAndRemoveTask()}.setNegativeButton("Hayır, kapat"){_,_->stopPlaybackAndClose()}.setNeutralButton("Vazgeç",null).create()
        dialog.setOnShowListener{dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setTextColor(orange);dialog.getButton(android.content.DialogInterface.BUTTON_NEGATIVE).setTextColor(Color.rgb(255,90,90));dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setTextColor(muted)};dialog.show()
    }
    private fun stopPlaybackAndClose(){try{controller?.stop();controller?.clearMediaItems()}catch(_:Exception){};try{stopService(Intent(this,RadioPlaybackService::class.java))}catch(_:Exception){};finishAndRemoveTask()}

    private fun showSettings(){
        val dialog=android.app.Dialog(this);val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(d(20),d(18),d(20),d(16));background=GradientDrawable().apply{setColor(Color.rgb(20,20,22));cornerRadius=d(28).toFloat();setStroke(d(1),Color.rgb(50,50,54))}}
        root.addView(TextView(this).apply{text="Ayarlar";textSize=25f;setTextColor(white);setTypeface(typeface,android.graphics.Typeface.BOLD)},LinearLayout.LayoutParams(-1,d(38)))
        root.addView(sectionLabel("OYNATMA"))
        root.addView(settingCard(R.drawable.ic_moon,"Uyku zamanlayıcısı",sleepTimerLabel()){val options=arrayOf("Kapalı","15 dakika","30 dakika","45 dakika","60 dakika","90 dakika");android.app.AlertDialog.Builder(this@MainActivity).setTitle("Uyku zamanlayıcısı").setItems(options){_,which->val mins=when(which){0->0L;1->15L;2->30L;3->45L;4->60L;else->90L};prefs.edit().putLong("sleep_until",if(mins==0L)0L else System.currentTimeMillis()+mins*60_000L).apply()}.show()})
        root.addView(settingCard(R.drawable.ic_play,"Arka planda çalma","Yayın açıkken uygulamadan çıkabilirsin"){android.widget.Toast.makeText(this,"Arka planda çalma etkin: medya servisi yayını sürdürüyor.",android.widget.Toast.LENGTH_SHORT).show()})
        root.addView(sectionLabel("GÖRÜNÜM"));root.addView(settingCard(R.drawable.ic_bolt,"Akıcı animasyonlar","Kartlar ve spectrum efektleri açık",null));root.addView(settingCard(R.drawable.ic_settings,"Keyfe Keder teması","Turuncu imza renk",null));root.addView(sectionLabel("UYGULAMA"));root.addView(settingCard(R.drawable.ic_radio,"Keyfe Keder Radyo","Android • "+BuildConfig.VERSION_NAME,null));dialog.setContentView(root);dialog.window?.setBackgroundDrawableResource(android.R.color.transparent);dialog.show();dialog.window?.setLayout(-1,-2)
    }
    private fun sectionLabel(value:String)=TextView(this).apply{text=value;textSize=10f;setTextColor(orange);setTypeface(typeface,android.graphics.Typeface.BOLD);setPadding(d(4),d(14),d(4),d(7))}
    private fun settingCard(icon:Int,name:String,detail:String,action:(()->Unit)?):View{val card=LinearLayout(this).apply{orientation=LinearLayout.HORIZONTAL;gravity=Gravity.CENTER_VERTICAL;setPadding(d(14),d(9),d(12),d(9));background=rounded(surface2,18);if(action!=null)setOnClickListener{tap(this);action.invoke()}};card.addView(ImageView(this).apply{setImageResource(icon);setColorFilter(orange);scaleType=ImageView.ScaleType.CENTER_INSIDE;setPadding(d(10),d(13),d(10),d(13))},LinearLayout.LayoutParams(d(42),d(50)));val info=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_VERTICAL;layoutParams=LinearLayout.LayoutParams(0,-2,1f)};info.addView(TextView(this).apply{text=name;textSize=14f;setTextColor(white);setTypeface(typeface,android.graphics.Typeface.BOLD)});info.addView(TextView(this).apply{text=detail;textSize=10f;setTextColor(muted)});card.addView(info);return card}
    private fun sleepTimerLabel():String{val until=prefs.getLong("sleep_until",0L);if(until<=System.currentTimeMillis())return "Kapalı";return "Yaklaşık "+(((until-System.currentTimeMillis())/60000L).coerceAtLeast(1L))+" dk kaldı"}

    private fun showFullPlayer(){
        if(isFinishing||isDestroyed)return
        val initial=stations.getOrNull(currentIndex)?:return
        val dialog=android.app.Dialog(this);fullPlayerDialog=dialog
        val root=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;gravity=Gravity.CENTER_HORIZONTAL;setPadding(d(22),d(10),d(22),d(18));background=GradientDrawable().apply{setColor(bg);cornerRadii=floatArrayOf(d(30).toFloat(),d(30).toFloat(),d(30).toFloat(),d(30).toFloat(),0f,0f,0f,0f);setStroke(d(1),Color.rgb(58,45,38))}}
        val top=LinearLayout(this).apply{gravity=Gravity.CENTER_VERTICAL}
        val close=TextView(this).apply{text="⌄";textSize=30f;setTextColor(muted);gravity=Gravity.CENTER;setOnClickListener{dialog.dismiss()}}
        top.addView(close,LinearLayout.LayoutParams(d(54),d(48)))
        top.addView(TextView(this).apply{text="ŞİMDİ ÇALIYOR";textSize=10f;setTextColor(orange);gravity=Gravity.CENTER;background=rounded(Color.rgb(43,28,20),16);letterSpacing=0.06f},LinearLayout.LayoutParams(-2,d(30)).apply{gravity=Gravity.CENTER})
        top.addView(View(this),LinearLayout.LayoutParams(0,1,1f))
        val favorite=ImageButton(this).apply{setImageResource(if(isFavorite(initial)) R.drawable.ic_heart else R.drawable.ic_heart_outline);setColorFilter(if(isFavorite(initial))orange else white);setBackgroundColor(Color.TRANSPARENT)}
        top.addView(favorite,LinearLayout.LayoutParams(d(48),d(48)));root.addView(top,LinearLayout.LayoutParams(-1,d(48)))
        val frame=FrameLayout(this).apply{layoutParams=LinearLayout.LayoutParams(d(226),d(226)).apply{topMargin=d(8);bottomMargin=d(18)};background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(Color.rgb(22,22,24));setStroke(d(2),Color.rgb(92,48,22))};elevation=d(5).toFloat()}
        val logo=StationArtworkView(this).apply{background=rounded(Color.rgb(24,24,25),24);bind(initial.name,initial.genre,initial.logoUrl);setPlaying(controller?.isPlaying==true)}
        frame.addView(logo,FrameLayout.LayoutParams(d(210),d(210)).apply{gravity=Gravity.CENTER});root.addView(frame)
        val stationName=TextView(this).apply{text=initial.name;textSize=25f;setTextColor(white);gravity=Gravity.CENTER;setTypeface(typeface,android.graphics.Typeface.BOLD);maxLines=2};root.addView(stationName,LinearLayout.LayoutParams(-1,d(58)))
        val live=TextView(this).apply{text="●  CANLI YAYIN";textSize=11f;setTextColor(orange);gravity=Gravity.CENTER;letterSpacing=0.08f};root.addView(live,LinearLayout.LayoutParams(-1,d(28)))
        val track=TextView(this).apply{text=nowPlaying.ifBlank{"CANLI • "+initial.name};textSize=13f;setTextColor(white);gravity=Gravity.CENTER;maxLines=1;background=rounded(surface,20);setPadding(d(18),0,d(18),0)};root.addView(track,LinearLayout.LayoutParams(-1,d(42)))
        val bigSpectrum=AudioSpectrumView(this).apply{setPlaying(controller?.isPlaying==true)};root.addView(bigSpectrum,LinearLayout.LayoutParams(-1,d(54)).apply{topMargin=d(8);bottomMargin=d(8)})
        val controls=LinearLayout(this).apply{gravity=Gravity.CENTER}
        val previous=iconButton(R.drawable.ic_prev).apply{layoutParams=LinearLayout.LayoutParams(d(60),d(60))}
        val main=ImageButton(this).apply{setImageResource(if(controller?.isPlaying==true)R.drawable.ic_pause else R.drawable.ic_play);setColorFilter(white);background=GradientDrawable().apply{shape=GradientDrawable.OVAL;setColor(orange)};layoutParams=LinearLayout.LayoutParams(d(82),d(82)).apply{setMargins(d(20),0,d(20),0)}}
        val next=iconButton(R.drawable.ic_next).apply{layoutParams=LinearLayout.LayoutParams(d(60),d(60))}
        fun refresh(){if(!dialog.isShowing||isFinishing||isDestroyed)return;val st=stations.getOrNull(currentIndex)?:return;stationName.text=st.name;logo.bind(st.name,st.genre,st.logoUrl);logo.setPlaying(controller?.isPlaying==true);favorite.setImageResource(if(isFavorite(st)) R.drawable.ic_heart else R.drawable.ic_heart_outline);favorite.setColorFilter(if(isFavorite(st))orange else white);track.text=if(nowPlaying.isNotBlank()) nowPlaying else "Canlı yayın";live.text=if(controller?.isPlaying==true)"●  CANLI YAYIN"else"YAYIN HAZIR";live.setTextColor(if(controller?.isPlaying==true)orange else muted);main.setImageResource(if(controller?.isPlaying==true)R.drawable.ic_pause else R.drawable.ic_play);bigSpectrum.setPlaying(controller?.isPlaying==true)}
        fullPlayerRefresh={refresh()}
        favorite.setOnClickListener{val st=stations.getOrNull(currentIndex)?:return@setOnClickListener;toggleFavorite(st)}
        main.setOnClickListener{togglePlay()}
        previous.setOnClickListener{playRelative(-1)}
        next.setOnClickListener{playRelative(1)}
        controls.addView(previous);controls.addView(main);controls.addView(next);root.addView(controls,LinearLayout.LayoutParams(-1,d(92)))
        dialog.setContentView(root);dialog.window?.setBackgroundDrawableResource(android.R.color.transparent);dialog.setCanceledOnTouchOutside(true);dialog.setOnDismissListener{logo.setPlaying(false);bigSpectrum.setPlaying(false);fullPlayerRefresh=null;fullPlayerDialog=null};dialog.show();dialog.window?.setLayout(-1,(resources.displayMetrics.heightPixels*0.90f).toInt());refresh()
    }

    private fun iconButton(resId:Int)=ImageButton(this).apply{setImageResource(resId);setBackgroundColor(Color.TRANSPARENT);setColorFilter(white);layoutParams=LinearLayout.LayoutParams(d(52),d(52))}
    private fun rounded(color:Int,radiusDp:Int)=GradientDrawable().apply{setColor(color);cornerRadius=d(radiusDp).toFloat()}
    private fun tap(view:View){view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(70).withEndAction{view.animate().scaleX(1f).scaleY(1f).setDuration(130).start()}.start()}
    private fun d(value:Int)=(value*resources.displayMetrics.density).toInt()
    override fun onDestroy(){fullPlayerRefresh=null;fullPlayerDialog=null;handler.removeCallbacksAndMessages(null);controllerFuture?.let{MediaController.releaseFuture(it)};executor.shutdownNow();super.onDestroy()}
}
