package com.example.gcodus

import android.Manifest
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.viewpager2.widget.ViewPager2
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Banner(
    val gameId: String,
    val gameName: String,
    val version: String,
    val start: String?,
    val end: String?,
    val characters: List<String>,
    val next: Boolean
)

class MainActivity : AppCompatActivity() {
    companion object {
        const val CODE_FEED_URL = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/app/src/main/assets/codes_feed.json"
    }
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val countdownViews = mutableListOf<Pair<TextView, String>>()
    private val prefs by lazy { getSharedPreferences("g_codus", Context.MODE_PRIVATE) }
    private var codesFeed: CodesFeed = CodesFeed.empty()
    private var currentScreen = Screen.HOME
    private var previousScreen = Screen.HOME
    private var previousGameId: String? = null
    private var currentGameId: String? = null

    private val bg = Color.rgb(13, 14, 19)
    private val surface = Color.rgb(21, 23, 32)
    private val text = Color.rgb(245, 245, 247)
    private val muted = Color.rgb(165, 167, 177)
    private val purple = Color.rgb(138, 99, 232)

    private val gameMeta = listOf(
        GameMeta("genshin", "Genshin Impact", "genshin"),
        GameMeta("wuwa", "Wuthering Waves", "wuwa"),
        GameMeta("zzz", "Zenless Zone Zero", "zzz")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.enableEdgeToEdge(window)
        setContentView(R.layout.activity_main)

        val root = findViewById<FrameLayout>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = false

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (currentScreen) {
                    Screen.HOME -> finish()
                    Screen.GAME -> showHome()
                    Screen.TRACKING -> {
                        val game = currentGameId
                        if (previousScreen == Screen.GAME && game != null) showGame(game) else showHome()
                    }
                    Screen.WISHLIST -> showHome()
                }
            }
        })
        codesFeed = loadCachedCodes()
        requestNotificationPermission()
        scheduleCodeSync()
        scheduleNotificationSync()
        refreshCodesInBackground()
        showHome()
        startCountdownTicker()
    }

    private fun showHome() {
        previousScreen = currentScreen
        previousGameId = currentGameId
        currentScreen = Screen.HOME
        currentGameId = null
        countdownViews.clear()
        val root = findViewById<FrameLayout>(R.id.root)
        root.removeAllViews()

        val scroll = makeScroll()
        val column = makeColumn()
        column.addView(makeTopGameBar())
        column.addView(label("ИЗБРАННОЕ", 13f, muted, true).apply {
            letterSpacing = 0.14f
            setPadding(dp(4), dp(10), 0, dp(10))
        })

        val favorites = loadFeed().filter { isFavorite(it.id) }
        if (favorites.isEmpty()) {
            column.addView(emptyCard("Избранных нет"))
        } else {
            favorites.forEachIndexed { index, game ->
                column.addView(favoriteGameSection(game))
                if (index != favorites.lastIndex) column.addView(space(18))
            }
        }

        scroll.addView(column)
        root.addView(scroll)
    }

    private fun showGame(gameId: String) {
        previousScreen = currentScreen
        previousGameId = currentGameId
        currentScreen = Screen.GAME
        currentGameId = gameId
        countdownViews.clear()
        val game = loadFeed().firstOrNull { it.id == gameId } ?: return
        // Always reload the latest persisted/bundled promo feed when entering a game.
        // This prevents an old empty SharedPreferences cache from hiding valid codes.
        codesFeed = loadCachedCodes()
        val root = findViewById<FrameLayout>(R.id.root)
        root.removeAllViews()

        val scroll = makeScroll()
        val column = makeColumn()

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(this@MainActivity.text)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(8), 0)
            setOnClickListener { showHome() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(50)))

        val title = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        title.addView(label(game.name, 22f, text, true))
        title.addView(label("Раздел игры", 13f, muted, false).apply {
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f))

        val star = TextView(this).apply {
            text = if (isFavorite(game.id)) "★" else "☆"
            textSize = 31f
            setTextColor(if (isFavorite(game.id)) Color.rgb(255, 211, 76) else muted)
            gravity = Gravity.CENTER
            setOnClickListener {
                setFavorite(game.id, !isFavorite(game.id))
                showGame(game.id)
            }
        }
        header.addView(star, LinearLayout.LayoutParams(dp(48), dp(50)))
        column.addView(header)

        val pageButtons = LinearLayout(this).apply {
            background = roundedDrawable(surface, 16f)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val bannerButton = TextView(this).apply {
            text = "ИНФОРМАЦИЯ О БАННЕРАХ"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(13), dp(8), dp(13))
        }
        val codeButton = TextView(this).apply {
            text = "ПРОМОКОДЫ"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(13), dp(8), dp(13))
        }
        val trackingButton = TextView(this).apply {
            text = "ОТСЛЕЖИВАНИЕ"
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(13), dp(8), dp(13))
        }
        pageButtons.addView(bannerButton, LinearLayout.LayoutParams(0, -2, 1f))
        pageButtons.addView(codeButton, LinearLayout.LayoutParams(0, -2, 1f))
        pageButtons.addView(trackingButton, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(pageButtons)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
        }
        column.addView(content)

        fun selectedButton(button: TextView, selected: Boolean) {
            button.background = roundedDrawable(
                if (selected) gameAccent(game.id) else Color.TRANSPARENT,
                13f
            )
            button.setTextColor(if (selected) Color.WHITE else muted)
        }

        fun showTrackingPage() {
            content.removeAllViews()
            selectedButton(bannerButton, false)
            selectedButton(codeButton, false)
            selectedButton(trackingButton, true)
            content.addView(trackingSection(game.id))
        }

        fun showBannerPage() {
            content.removeAllViews()
            selectedButton(bannerButton, true)
            selectedButton(codeButton, false)
            content.addView(sectionLabel("БАННЕРЫ СЕЙЧАС"))
            content.addView(bannerPager(game.current, false, game.id))
            content.addView(sectionLabel("СЛЕДУЮЩИЕ БАННЕРЫ").apply {
                setPadding(0, dp(22), 0, dp(8))
            })
            content.addView(bannerPager(game.next, true, game.id))
        }

        fun showCodePage() {
            // Re-read the feed before rendering the promo-code page so the page
            // never depends on the value captured at application startup.
            codesFeed = loadCachedCodes()
            content.removeAllViews()
            selectedButton(bannerButton, false)
            selectedButton(codeButton, true)
            content.addView(sectionLabel("ПРОМОКОДЫ"))
            content.addView(codeSection(game.id))
        }

        bannerButton.setOnClickListener { showBannerPage() }
        codeButton.setOnClickListener { showCodePage() }
        trackingButton.setOnClickListener { showTrackingPage() }
        showBannerPage()

        scroll.addView(column)
        root.addView(scroll)
    }

    private fun makeTopGameBar(): View {
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(8))
        }

        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(label("G-Codus", 30f, text, true), LinearLayout.LayoutParams(0, -2, 1f))
        titleRow.addView(label("Баннеры", 14f, muted, false).apply {
            gravity = Gravity.CENTER_VERTICAL
        })
        wrapper.addView(titleRow)

        val icons = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(14), 0, dp(2))
        }

        gameMeta.forEach { meta ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(4), 0, dp(4), 0)
                setOnClickListener { showGame(meta.id) }
            }

            val iconFrame = FrameLayout(this).apply {
                background = roundedDrawable(gameAccent(meta.id), 18f)
                clipToOutline = true
            }
            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(7), dp(7), dp(7), dp(7))
            }
            val resId = resources.getIdentifier("game_" + meta.resourceName, "drawable", packageName)
            if (resId != 0) icon.setImageResource(resId) else icon.setImageDrawable(null)
            iconFrame.addView(icon, FrameLayout.LayoutParams(-1, -1))
            item.addView(iconFrame, LinearLayout.LayoutParams(dp(68), dp(68)))

            item.addView(label(
                meta.name, 11f, muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            })

            icons.addView(item, LinearLayout.LayoutParams(0, dp(94), 1f))
        }

        wrapper.addView(icons)

        val wishlist = TextView(this).apply {
            text = "♡  Мой вишлист"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(this@MainActivity.text)
            background = roundedDrawable(surface, 18f)
            setPadding(dp(12), dp(15), dp(12), dp(15))
            setOnClickListener { showWishlist() }
        }
        wrapper.addView(wishlist, LinearLayout.LayoutParams(-1, dp(52)).apply {
            topMargin = dp(8)
        })
        return wrapper
    }

    private fun favoriteGameSection(game: GameFeed): View {
        val block = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val titleRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(8))
        }
        val dot = View(this).apply { background = roundedDrawable(gameAccent(game.id), 99f) }
        titleRow.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(9) })
        titleRow.addView(label(game.name, 21f, text, true))
        block.addView(titleRow)
        block.addView(sectionLabel("БАННЕРЫ СЕЙЧАС"))
        block.addView(bannerPager(game.current, false, game.id))
        block.addView(sectionLabel("СЛЕДУЮЩИЕ БАННЕРЫ").apply {
            setPadding(0, dp(22), 0, dp(8))
        })
        block.addView(bannerPager(game.next, true, game.id))
        return block
    }

    private fun sectionLabel(value: String) = label(value, 12f, muted, true).apply {
        letterSpacing = 0.12f
        setPadding(dp(4), dp(4), 0, dp(8))
    }

    private fun bannerPager(banners: List<Banner>, isNext: Boolean, gameId: String): View {
        if (banners.isEmpty()) {
            return emptyCard(if (isNext) "Следующий баннер ещё не объявлен" else "Активный баннер не найден")
        }

        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val pager = ViewPager2(this).apply {
            orientation = ViewPager2.ORIENTATION_HORIZONTAL
            offscreenPageLimit = 1
            setPageTransformer { page, position ->
                val factor = (1f - kotlin.math.abs(position)).coerceIn(0f, 1f)
                page.alpha = 0.65f + factor * 0.35f
                page.scaleY = 0.96f + factor * 0.04f
            }
        }
        pager.adapter = BannerPagerAdapter(banners) { banner -> bannerCard(banner, isNext, gameId) }
        wrapper.addView(pager, LinearLayout.LayoutParams(-1, dp(365)))

        val dots = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        banners.forEachIndexed { i, _ ->
            val dot = View(this).apply {
                background = roundedDrawable(if (i == 0) purple else Color.rgb(105, 107, 116), 50f)
            }
            dots.addView(dot, LinearLayout.LayoutParams(if (i == 0) dp(32) else dp(8), dp(6)).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        pager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                for (i in 0 until dots.childCount) {
                    val d = dots.getChildAt(i)
                    val lp = d.layoutParams as LinearLayout.LayoutParams
                    lp.width = dp(if (i == position) 32 else 8)
                    d.layoutParams = lp
                    d.background = roundedDrawable(
                        if (i == position) purple else Color.rgb(105, 107, 116), 50f
                    )
                }
            }
        })
        wrapper.addView(dots)
        return wrapper
    }

    private fun bannerCard(banner: Banner, isNext: Boolean, gameId: String): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
            background = roundedDrawable(surface, 22f)
        }

        val art = FrameLayout(this).apply {
            background = roundedDrawable(gameAccent(gameId), 18f)
            clipToOutline = true
        }
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(gameAccent(gameId))
            clipToOutline = true
        }
        art.addView(image, FrameLayout.LayoutParams(-1, -1))

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.argb(95, 0, 0, 0))
        }
        overlay.addView(label("5★", 28f, Color.WHITE, true))
        overlay.addView(label(banner.characters.firstOrNull() ?: "—", 18f, Color.WHITE, true).apply {
            maxLines = 2
        })
        art.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        loadPortrait(image, banner.characters.firstOrNull().orEmpty(), gameId)
        card.addView(art, LinearLayout.LayoutParams(dp(178), dp(349)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(4))
        }
        info.addView(label("5★", 17f, Color.rgb(255, 211, 76), true))
        info.addView(label(banner.characters.firstOrNull() ?: "Баннер", 22f, text, true).apply {
            setPadding(0, dp(4), 0, dp(2))
            maxLines = 2
        })
        info.addView(label(banner.version, 13f, muted, false))
        info.addView(Space(this), LinearLayout.LayoutParams(1, 0, 1f))

        val time = label("", if (isNext) 15f else 19f, text, true)
        if (isNext) {
            time.text = if (banner.start != null) "Начало\n" + formatDate(banner.start)
            else "Дата начала\nне объявлена"
        } else {
            time.text = "До окончания\n—"
            if (banner.end != null) countdownViews.add(time to banner.end)
        }
        info.addView(time)
        info.addView(label(if (isNext) "ПРЕДСТОИТ" else "СЕЙЧАС", 11f,
            if (isNext) muted else gameAccent(gameId), true).apply {
            setPadding(0, dp(10), 0, dp(2))
        })
        card.addView(info, LinearLayout.LayoutParams(0, -1, 1f))
        return card
    }

    private fun scheduleCodeSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<CodeSyncWorker>(15, java.util.concurrent.TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "g_codus_code_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001)
        }
    }

    private fun scheduleNotificationSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "g_codus_notifications",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun refreshCodesInBackground() {
        executor.execute {
            try {
                val connection = java.net.URL(CODE_FEED_URL).openConnection()
                connection.connectTimeout = 12000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "G-Codus/1.0")
                val json = connection.getInputStream().bufferedReader().use { it.readText() }
                codesFeed = parseCodesFeed(json)
                prefs.edit().putString("codes_feed", json).apply()
                runOnUiThread { showHome() }
            } catch (_: Exception) { }
        }
    }

    private fun loadCachedCodes(): CodesFeed {
        // Prefer the last successfully downloaded feed. On a fresh install there
        // is no SharedPreferences entry yet, so immediately fall back to the
        // codes_feed.json bundled into the APK. This guarantees that the code
        // page is populated even before the first background HTTP refresh.
        val cached = prefs.getString("codes_feed", null)
        if (!cached.isNullOrBlank()) {
            try {
                val parsed = parseCodesFeed(cached)
                // An old empty cache must never hide a valid bundled feed.
                if (parsed.active.isNotEmpty() || parsed.expired.isNotEmpty()) {
                    return parsed
                }
            } catch (_: Exception) {
                // Continue to the bundled feed.
            }
        }

        return try {
            val bundled = assets.open("codes_feed.json").bufferedReader().use { it.readText() }
            parseCodesFeed(bundled)
        } catch (_: Exception) {
            CodesFeed.empty()
        }
    }

    private fun parseCodesFeed(json: String): CodesFeed {
        val root = JSONObject(json)
        fun read(key: String, expired: Boolean): List<PromoCode> {
            val arr = root.optJSONArray(key) ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PromoCode(
                    o.optString("game"), o.optString("code"), o.optString("rewards"),
                    o.optString("source"), o.optString("expires_at"),
                    o.optString("expired_at"), if (expired) "expired" else "active"
                )
            }
        }
        return CodesFeed(read("active", false), read("expired", true))
    }

    private fun codeSection(gameId: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun sameGame(value: String): Boolean {
            val normalized = value.trim().lowercase()
            return when (gameId.lowercase()) {
                "genshin" -> normalized in setOf("genshin", "genshinimpact", "genshin impact")
                "wuwa" -> normalized in setOf("wuwa", "wutheringwaves", "wuthering waves", "wutheringwave")
                "zzz" -> normalized in setOf("zzz", "zenless", "zenlesszonezero", "zenless zone zero")
                else -> normalized == gameId.lowercase()
            }
        }
        val active = codesFeed.active.filter { sameGame(it.game) }
        val expired = codesFeed.expired.filter { sameGame(it.game) }

        box.addView(sectionLabel("ДЕЙСТВУЮЩИЕ").apply {
            setPadding(dp(4), dp(4), 0, dp(8))
        })
        if (active.isEmpty()) box.addView(emptyCard("Действующих промокодов сейчас нет"))
        active.forEach { box.addView(codeCard(it)) }

        box.addView(sectionLabel("ПРОСРОЧЕННЫЕ").apply {
            setPadding(dp(4), dp(22), 0, dp(8))
        })
        if (expired.isEmpty()) box.addView(emptyCard("Просроченных промокодов нет"))
        expired.take(20).forEach { box.addView(codeCard(it)) }
        return box
    }

    private fun codeCard(code: PromoCode): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(10), dp(12))
            background = roundedDrawable(surface, 18f)
        }
        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(label(code.code, 18f, text, true), LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(label(
            if (code.status == "active") "ДЕЙСТВИТЕЛЕН" else "ПРОСРОЧЕН",
            10f,
            if (code.status == "active") Color.rgb(92, 214, 139) else muted,
            true
        ))
        card.addView(top)
        if (code.rewards.isNotBlank()) card.addView(label(code.rewards, 12f, muted, false).apply {
            setPadding(0, dp(6), 0, 0)
            maxLines = 3
        })
        card.addView(label("Источник: " + code.source, 10f, muted, false).apply {
            setPadding(0, dp(4), 0, 0)
        })
        if (code.status == "active") {
            card.setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("G-Codus", code.code))
                Toast.makeText(this, "Код скопирован", Toast.LENGTH_SHORT).show()
            }
        }
        return card.apply {
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) }
        }
    }

    private fun showWishlist() {
        previousScreen = currentScreen
        previousGameId = currentGameId
        currentScreen = Screen.WISHLIST
        currentGameId = null
        countdownViews.clear()
        val root = findViewById<FrameLayout>(R.id.root)
        root.removeAllViews()
        val scroll = makeScroll()
        val column = makeColumn()
        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(12))
        }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(this@MainActivity.text)
            gravity = Gravity.CENTER
            setOnClickListener { showHome() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(50)))
        header.addView(label("Мой вишлист", 24f, text, true), LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(header)
        column.addView(sectionLabel("ОТСЛЕЖИВАЕМЫЕ ПЕРСОНАЖИ"))
        val search = EditText(this).apply {
            hint = "Поиск персонажа"
            textSize = 15f
            setSingleLine(true)
            setTextColor(this@MainActivity.text)
            setHintTextColor(muted)
            background = roundedDrawable(surface, 16f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        column.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        val grid = FrameLayout(this)
        column.addView(grid)
        fun renderWishlist(query: String) {
            grid.removeAllViews()
            grid.addView(trackingGrid(null, query))
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { renderWishlist(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        renderWishlist("")
        scroll.addView(column)
        root.addView(scroll)
    }

    private fun trackingSection(gameId: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(sectionLabel("ОТСЛЕЖИВАЕМЫЕ ПЕРСОНАЖИ"))
        val search = EditText(this).apply {
            hint = "Поиск персонажа"
            textSize = 15f
            setSingleLine(true)
            setTextColor(this@MainActivity.text)
            setHintTextColor(muted)
            background = roundedDrawable(surface, 16f)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(search, LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) })
        val grid = FrameLayout(this)
        box.addView(grid)
        fun render(query: String) {
            grid.removeAllViews()
            grid.addView(trackingGrid(gameId, query))
        }
        search.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render(s?.toString().orEmpty()) }
            override fun afterTextChanged(s: android.text.Editable?) = Unit
        })
        render("")
        return box
    }

    private fun trackingGrid(gameId: String?, query: String?): View {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val games = if (gameId == null) gameMeta else gameMeta.filter { it.id == gameId }
        val normalizedQuery = query?.trim()?.lowercase().orEmpty()
        val entries = mutableListOf<TrackedCharacter>()
        games.forEach { meta ->
            listCharacterFiles(meta.id).forEach { file ->
                val name = characterDisplayName(file)
                if ((gameId != null || isTracked(meta.id, file)) &&
                    (normalizedQuery.isBlank() || name.lowercase().contains(normalizedQuery))) {
                    entries += TrackedCharacter(meta.id, meta.name, name, file)
                }
            }
        }
        entries.sortWith(compareByDescending<TrackedCharacter> { isTracked(it.gameId, it.file) }.thenBy { it.name.lowercase() })
        if (entries.isEmpty()) {
            holder.addView(emptyCard(if (normalizedQuery.isBlank()) "Персонажей пока нет" else "Ничего не найдено"))
            return holder
        }
        var row: LinearLayout? = null
        entries.forEachIndexed { index, character ->
            if (index % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                holder.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            row?.addView(trackingCharacterCell(character), LinearLayout.LayoutParams(0, dp(178), 1f).apply {
                marginStart = if (index % 3 == 0) 0 else dp(3)
                marginEnd = dp(3)
                bottomMargin = dp(8)
            })
        }
        return holder
    }

    private fun trackingCharacterCell(character: TrackedCharacter): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(surface, 16f)
            clipToOutline = true
            setOnClickListener { toggleTracked(character.gameId, character.file); refreshCurrentScreen() }
        }
        val imageFrame = FrameLayout(this)
        val image = ImageView(this).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        imageFrame.addView(image, FrameLayout.LayoutParams(-1, dp(136)))
        loadTrackingPortrait(image, character.file, character.gameId)
        val heart = TextView(this).apply {
            text = if (isTracked(character.gameId, character.file)) "♥" else "♡"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(if (isTracked(character.gameId, character.file)) Color.rgb(255, 91, 123) else Color.WHITE)
            background = roundedDrawable(Color.argb(145, 0, 0, 0), 99f)
            setOnClickListener { toggleTracked(character.gameId, character.file); refreshCurrentScreen() }
        }
        imageFrame.addView(heart, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(7)
            marginEnd = dp(7)
        })
        cell.addView(imageFrame, LinearLayout.LayoutParams(-1, dp(136)))
        cell.addView(label(character.name, 12f, text, true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(4), dp(7), dp(4), dp(7))
        }, LinearLayout.LayoutParams(-1, dp(42)))
        return cell
    }

    private fun listCharacterFiles(gameId: String): List<String> {
        val folder = gameFolder(gameId) ?: return emptyList()
        return try { assets.list(folder).orEmpty().filter { it.endsWith(".webp", true) }.sorted() }
        catch (_: Exception) { emptyList() }
    }

    private fun gameFolder(gameId: String): String? = when (gameId) {
        "genshin" -> "genshin"
        "wuwa" -> "wuthering_waves"
        "zzz" -> "zenless_zone_zero"
        else -> null
    }

    private fun characterDisplayName(file: String): String {
        val base = file.substringBeforeLast(".")
        val overrides = mapOf(
            "arataki-itto" to "Arataki Itto",
            "al-haitham" to "Alhaitham",
            "yumemizuki-mizuki" to "Yumemizuki Mizuki",
            "yae-miko" to "Yae Miko",
            "yun-jin" to "Yun Jin",
            "anby-demara-soldier-0" to "Anby: Soldier 0",
            "orhpie-and-magus" to "Orphie & Magus",
            "orhpie-magus" to "Orphie & Magus",
            "luuk-herssen" to "Luuk Herssen"
        )
        overrides[base]?.let { return it }
        return base.split("-").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun trackingKey(gameId: String, file: String) = "tracked_" + gameId + "_" + file
    private fun isTracked(gameId: String, file: String): Boolean = prefs.getBoolean(trackingKey(gameId, file), false)
    private fun toggleTracked(gameId: String, file: String) { prefs.edit().putBoolean(trackingKey(gameId, file), !isTracked(gameId, file)).apply() }

    private fun loadTrackingPortrait(image: ImageView, file: String, gameId: String) {
        val folder = gameFolder(gameId) ?: return
        image.setImageDrawable(null)
        val trackingPath = "tracking/" + folder + "/" + file
        val legacyPath = folder + "/" + file
        try {
            assets.open(trackingPath).use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                if (bitmap != null) { image.setImageBitmap(bitmap); return }
            }
        } catch (_: Exception) { }
        try {
            assets.open(legacyPath).use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                if (bitmap != null) image.setImageBitmap(bitmap)
            }
        } catch (_: Exception) { }
    }

    private fun refreshCurrentScreen() {
        when (currentScreen) {
            Screen.TRACKING -> currentGameId?.let { showTracking(it) } ?: showWishlist()
            Screen.WISHLIST -> showWishlist()
            Screen.GAME -> currentGameId?.let { showGame(it) }
            Screen.HOME -> showHome()
        }
    }

    private fun showTracking(gameId: String) {
        previousScreen = currentScreen
        previousGameId = currentGameId
        currentScreen = Screen.TRACKING
        currentGameId = gameId
        countdownViews.clear()
        val root = findViewById<FrameLayout>(R.id.root)
        root.removeAllViews()
        val scroll = makeScroll()
        val column = makeColumn()
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, 0, 0, dp(12)) }
        val back = TextView(this).apply {
            text = "‹"
            textSize = 38f
            setTextColor(this@MainActivity.text)
            gravity = Gravity.CENTER
            setOnClickListener { showGame(gameId) }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(50)))
        header.addView(label(gameMeta.first { it.id == gameId }.name, 24f, text, true), LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(header)
        column.addView(trackingSection(gameId))
        scroll.addView(column)
        root.addView(scroll)
    }

    private enum class Screen { HOME, GAME, TRACKING, WISHLIST }
    data class TrackedCharacter(val gameId: String, val gameName: String, val name: String, val file: String)

    private inner class BannerPagerAdapter(
        private val items: List<Banner>,
        private val factory: (Banner) -> View
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<BannerPagerAdapter.Holder>() {
        inner class Holder(val container: FrameLayout) :
            androidx.recyclerview.widget.RecyclerView.ViewHolder(container)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
            Holder(FrameLayout(this@MainActivity).apply {
                layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, -1)
            })

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.container.removeAllViews()
            holder.container.addView(factory(items[position]))
        }

        override fun getItemCount() = items.size
    }

    private fun loadPortrait(image: ImageView, character: String, gameId: String) {
        // Portraits are STRICTLY LOCAL. The only source is the user-provided
        // images_big/<game>/ files bundled into the APK. No CDN/network fallback.
        image.setImageDrawable(null)

        val normalized = character.lowercase()
            .replace("’", "")
            .replace("'", "")
            .replace(":", "")
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val characterFile = when (normalized) {
            "anby-soldier-0", "soldier-0-anby", "anby-demara-soldier-0" -> "anby-demara-soldier-0.webp"
            else -> "$normalized.webp"
        }

        val gameFolder = when (gameId) {
            "genshin" -> "genshin"
            "wuwa" -> "wuthering_waves"
            "zzz" -> "zenless_zone_zero"
            else -> return
        }

        // images_big is an external asset source directory; Android places its contents at the asset root.
        // Therefore the bundled path is <gameFolder>/<characterFile>, not images_big/<...>.
        val assetPath = "$gameFolder/$characterFile"

        try {
            assets.open(assetPath).use { input ->
                val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                if (bitmap != null) {
                    image.setImageBitmap(bitmap)
                }
            }
        } catch (_: Exception) {
            // Missing mapping = blank image. Never substitute another character.
        }
    }

    private fun startCountdownTicker() {
        executor.scheduleAtFixedRate({
            runOnUiThread {
                val now = Instant.now()
                countdownViews.forEach { pair ->
                    try {
                        val target = OffsetDateTime.parse(pair.second).toInstant()
                        val seconds = Duration.between(now, target).seconds.coerceAtLeast(0)
                        pair.first.text = "До окончания\n" + formatCountdown(seconds)
                    } catch (_: Exception) { }
                }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun loadFeed(): List<GameFeed> {
        val source = assets.open("banner_feed.json").bufferedReader()
            .use(BufferedReader::readText)
        val games = JSONObject(source).getJSONArray("games")
        val result = mutableListOf<GameFeed>()
        for (i in 0 until games.length()) {
            val g = games.getJSONObject(i)
            result += GameFeed(
                g.getString("id"),
                g.getString("name"),
                parseBanners(g, "current"),
                parseBanners(g, "next")
            )
        }
        return result
    }

    private fun parseBanners(game: JSONObject, key: String): List<Banner> {
        val b = game.getJSONObject(key)
        val arr: JSONArray = b.optJSONArray("five_star") ?: JSONArray()
        val result = mutableListOf<Banner>()
        for (i in 0 until arr.length()) {
            result += Banner(
                game.getString("id"),
                game.getString("name"),
                b.optString("version"),
                b.optString("start").takeIf { it.isNotBlank() && it != "null" },
                b.optString("end").takeIf { it.isNotBlank() && it != "null" },
                listOf(arr.getString(i)),
                key == "next"
            )
        }
        return result
    }

    private fun isFavorite(gameId: String): Boolean =
        prefs.getBoolean("favorite_$gameId", false)

    private fun setFavorite(gameId: String, value: Boolean) {
        prefs.edit().putBoolean("favorite_$gameId", value).apply()
    }

    private fun makeScroll() = ScrollView(this).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun makeColumn() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(32))
    }

    private fun emptyCard(value: String) = TextView(this).apply {
        text = value
        setTextColor(muted)
        textSize = 15f
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(32), dp(12), dp(32))
        background = roundedDrawable(surface, 20f)
    }

    private fun space(height: Int) = Space(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun formatCountdown(seconds: Long): String {
        val days = seconds / 86400
        val hours = (seconds % 86400) / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (days > 0) "%02dd %02dh %02dm".format(days, hours, minutes)
        else "%02dh %02dm %02ds".format(hours, minutes, secs)
    }

    private fun formatDate(value: String): String = try {
        OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd.MM.yyyy • HH:mm"))
    } catch (_: Exception) {
        value
    }

    private fun gameAccent(id: String): Int = when (id) {
        "genshin" -> Color.rgb(155, 114, 255)
        "wuwa" -> Color.rgb(79, 168, 255)
        "zzz" -> Color.rgb(240, 182, 77)
        else -> purple
    }

    private fun label(value: String, size: Float, color: Int, bold: Boolean) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            typeface = if (bold) android.graphics.Typeface.DEFAULT_BOLD
            else android.graphics.Typeface.DEFAULT
        }

    private fun roundedDrawable(color: Int, radius: Float) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).toInt()

    data class PromoCode(
        val game: String,
        val code: String,
        val rewards: String,
        val source: String,
        val expiresAt: String,
        val expiredAt: String,
        val status: String
    )

    data class CodesFeed(
        val active: List<PromoCode>,
        val expired: List<PromoCode>
    ) { companion object { fun empty() = CodesFeed(emptyList(), emptyList()) } }

    data class GameFeed(
        val id: String,
        val name: String,
        val current: List<Banner>,
        val next: List<Banner>
    )

    data class GameMeta(
        val id: String,
        val name: String,
        val resourceName: String
    )
}
