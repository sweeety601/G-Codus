package com.example.gcodus

import android.Manifest
import android.content.Context
import android.graphics.Color
import android.graphics.Bitmap
import android.util.LruCache
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
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
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
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
    val fourStars: List<String>,
    val next: Boolean,
    val unconfirmed: Boolean = false
)

class MainActivity : AppCompatActivity() {
    companion object {}
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val countdownExecutor = Executors.newSingleThreadScheduledExecutor()
    private val imageExecutor = Executors.newFixedThreadPool(4)
    private val portraitCache = LruCache<String, Bitmap>(48)
    private val countdownViews = mutableListOf<Pair<TextView, String>>()
    private val prefs by lazy { getSharedPreferences("g_codus", Context.MODE_PRIVATE) }
    private var codesFeed: CodesFeed = CodesFeed.empty()
    private var currentScreen = Screen.HOME
    private var previousScreen = Screen.HOME
    private var previousGameId: String? = null
    private var currentGameId: String? = null
    private var onlineCharacters: List<OnlineCharacter> = emptyList()
    private var bannerFeedJson: String? = null

    private val bg = Color.rgb(13, 14, 19)
    private val surface = Color.rgb(21, 23, 32)
    private val text = Color.rgb(245, 245, 247)
    private val muted = Color.rgb(165, 167, 177)
    private val purple = Color.rgb(138, 99, 232)

    private val gameMeta = listOf(
        GameMeta("genshin", "Genshin Impact", "genshin"),
        GameMeta("wuwa", "Wuthering Waves", "wuwa"),
        GameMeta("zzz", "Zenless Zone Zero", "zzz"),
        GameMeta("starrail", "Honkai: Star Rail", "starrail"),
        GameMeta("endfield", "Arknights: Endfield", "endfield")
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
        bannerFeedJson = null
        requestNotificationPermission()
        scheduleCodeSync()
        scheduleNotificationSync()
        scheduleCharacterSync()
        refreshCodesInBackground()
        refreshBannerFeedInBackground()
        refreshCharacterDatabaseInBackground()
        migrateTrackingKeys()
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
        animateScreenIn(column)
    }

    private fun showGame(gameId: String) {
        previousScreen = currentScreen
        previousGameId = currentGameId
        currentScreen = Screen.GAME
        currentGameId = gameId
        countdownViews.clear()
        val game = loadFeed().firstOrNull { it.id == gameId }
        if (game == null) {
            val root = findViewById<FrameLayout>(R.id.root)
            root.removeAllViews()

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
            addPressEffect(back)
            header.addView(back, LinearLayout.LayoutParams(dp(42), dp(50)))
            val meta = gameMeta.firstOrNull { it.id == gameId }
            header.addView(
                label(meta?.name ?: gameId, 22f, text, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            column.addView(header)

            val retry = emptyCard("Данные баннеров недоступны. Нажмите для повтора.")
            retry.setOnClickListener {
                executor.execute {
                    try {
                        val fresh = BannerSource.fetchNormalized(this@MainActivity)
                        JSONObject(fresh).getJSONArray("games")
                        prefs.edit().putString("banner_feed", fresh).apply()
                        bannerFeedJson = fresh
                        runOnUiThread {
                            if (!isFinishing && currentScreen == Screen.GAME && currentGameId == gameId) {
                                showGame(gameId)
                            }
                        }
                    } catch (_: Exception) {
                        runOnUiThread {
                            if (!isFinishing && currentScreen == Screen.GAME && currentGameId == gameId) {
                                Toast.makeText(this@MainActivity, "Не удалось обновить баннеры", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
            column.addView(retry)

            val scroll = makeScroll()
            scroll.addView(column)
            root.addView(scroll)
            animateScreenIn(column)
            return
        }
        // Always reload the latest live promo snapshot when entering a game.
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
        addPressEffect(back)
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
        addPressEffect(star)
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
            setTextColor(Color.WHITE)
            setPadding(dp(8), dp(13), dp(8), dp(13))
        }
        addPressEffect(bannerButton)
        addPressEffect(codeButton)
        addPressEffect(trackingButton)
        pageButtons.addView(bannerButton, LinearLayout.LayoutParams(0, -2, 1f))
        pageButtons.addView(codeButton, LinearLayout.LayoutParams(0, -2, 1f))
        pageButtons.addView(trackingButton, LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(pageButtons)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, 0)
            // This container is populated asynchronously by animatePageChange().
            // Do not let the outer screen animation cancel that build animation.
            tag = "dynamic_page_content"
        }
        column.addView(content)

        fun selectedButton(button: TextView, selected: Boolean) {
            button.background = roundedDrawable(
                if (selected) gameAccent(game.id) else Color.TRANSPARENT,
                13f
            )
            button.setTextColor(
                Color.WHITE
            )
        }

        var pageAnimationRunning = false

        fun animatePageChange(build: () -> Unit) {
            if (pageAnimationRunning) return
            pageAnimationRunning = true
            content.animate().cancel()
            content.animate()
                .alpha(0f)
                .translationY(dp(8).toFloat())
                .setDuration(110)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .scaleX(0.985f)
                .scaleY(0.985f)
                .withEndAction {
                    content.removeAllViews()
                    build()
                    content.alpha = 0f
                    content.translationY = dp(-8).toFloat()
                    content.scaleX = 0.985f
                    content.scaleY = 0.985f
                    content.animate()
                        .alpha(1f)
                        .translationY(0f)
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(210)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .withEndAction { pageAnimationRunning = false }
                        .start()
                }
                .start()
        }

        fun showTrackingPage() {
            selectedButton(bannerButton, false)
            selectedButton(codeButton, false)
            selectedButton(trackingButton, true)
            animatePageChange {
                content.addView(trackingSection(game.id))
            }
        }

        fun showBannerPage() {
            selectedButton(bannerButton, true)
            selectedButton(codeButton, false)
            selectedButton(trackingButton, false)
            animatePageChange {
                content.addView(sectionLabel("БАННЕРЫ СЕЙЧАС"))
                content.addView(bannerPager(game.current, false, game.id))
                content.addView(sectionLabel("СЛЕДУЮЩИЕ БАННЕРЫ").apply {
                    setPadding(0, dp(22), 0, dp(8))
                })
                content.addView(bannerPager(game.next, true, game.id))
                if (game.upcoming.isNotEmpty()) {
                    content.addView(sectionLabel("СЛИВЫ • НЕ ПОДТВЕРЖДЕНО").apply {
                        setPadding(0, dp(22), 0, dp(8))
                    })
                    content.addView(bannerPager(game.upcoming, true, game.id))
                }
            }
        }

        fun showCodePage() {
            // Re-read the feed before rendering the promo-code page so the page
            // never depends on the value captured at application startup.
            codesFeed = loadCachedCodes()
            selectedButton(bannerButton, false)
            selectedButton(codeButton, true)
            selectedButton(trackingButton, false)
            animatePageChange {
                content.addView(sectionLabel("ПРОМОКОДЫ"))
                content.addView(codeSection(game.id))
            }
        }

        bannerButton.setOnClickListener { showBannerPage() }
        codeButton.setOnClickListener { showCodePage() }
        trackingButton.setOnClickListener { showTrackingPage() }
        showBannerPage()

        scroll.addView(column)
        root.addView(scroll)
        animateScreenIn(column)
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

        val icons = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            setPadding(0, 0, 0, 0)
        }
        val iconRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
        }
        icons.addView(iconRow, android.widget.FrameLayout.LayoutParams(-2, dp(118)))

        gameMeta.forEach { meta ->
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                setPadding(0, 0, 0, 0)
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
            if (resId != 0) icon.setImageResource(resId)
            iconFrame.addView(icon, FrameLayout.LayoutParams(-1, -1))
            addPressEffect(item)
            item.addView(iconFrame, LinearLayout.LayoutParams(dp(72), dp(72)))

            item.addView(label(
                meta.name, 11f, muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            }, LinearLayout.LayoutParams(dp(72), dp(38)))

            iconRow.addView(item, LinearLayout.LayoutParams(dp(80), dp(116)))
        }

        wrapper.addView(icons, LinearLayout.LayoutParams(-1, dp(122)))

        val wishlist = TextView(this).apply {
            text = "Мой вишлист"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(this@MainActivity.text)
            background = roundedDrawable(surface, 18f)
            setPadding(dp(12), dp(15), dp(12), dp(15))
            setOnClickListener { showWishlist() }
        }
        addPressEffect(wishlist)
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
        if (game.upcoming.isNotEmpty()) {
            block.addView(sectionLabel("СЛИВЫ • НЕ ПОДТВЕРЖДЕНО").apply {
                setPadding(0, dp(22), 0, dp(8))
            })
            block.addView(bannerPager(game.upcoming, true, game.id))
        }
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
                page.scaleX = 0.965f + factor * 0.035f
                page.scaleY = 0.96f + factor * 0.04f
                page.translationY = dp(4) * kotlin.math.abs(position)
                page.rotation = position.coerceIn(-1f, 1f) * -1.2f
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

        val bannerRarity = if (gameId == "endfield") "6★" else "5★"

        val overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.BOTTOM
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.argb(95, 0, 0, 0))
        }
        overlay.addView(label(bannerRarity, 28f, Color.WHITE, true))
        val displayBannerCharacters = banner.characters
            .map { canonicalCharacterDisplayName(gameId, it) }
            .filter { it.isNotBlank() }
            .distinct()
        val bannerCharacterText = displayBannerCharacters.joinToString(" • ").ifBlank { "—" }
        overlay.addView(label(bannerCharacterText, 18f, Color.WHITE, true).apply {
            maxLines = 2
        })
        art.addView(overlay, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))

        loadPortrait(image, banner.characters.firstOrNull().orEmpty(), gameId)
        card.addView(art, LinearLayout.LayoutParams(dp(178), dp(349)))

        val info = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(8), dp(8), dp(4))
        }
        info.addView(label(bannerRarity, 17f, Color.rgb(255, 211, 76), true))
        val fiveName = displayBannerCharacters.joinToString(" • ").ifBlank { "Баннер" }
        info.addView(label(fiveName, 22f, text, true).apply {
            setPadding(0, dp(4), 0, dp(2))
            maxLines = 2
        })
        info.addView(label(banner.version, 13f, muted, false))

        if (banner.unconfirmed) {
            info.addView(label("Не подтверждено", 11f, Color.rgb(255, 170, 80), true).apply {
                setPadding(0, dp(5), 0, dp(2))
            })
        }

        if (banner.fourStars.isNotEmpty()) {
            val fourStarRow = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(10), 0, dp(4))
            }
            banner.fourStars.take(3).forEach { character ->
                val portrait = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = roundedDrawable(Color.rgb(42, 44, 54), 8f)
                    clipToOutline = true
                }
                loadPortrait(portrait, character, gameId)
                fourStarRow.addView(portrait, LinearLayout.LayoutParams(dp(34), dp(34)).apply {
                    marginEnd = dp(6)
                })
            }
            info.addView(fourStarRow)
        }

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

        card.alpha = 0f
        card.scaleX = 0.975f
        card.scaleY = 0.975f
        card.post {
            card.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
        return card
    }

    private fun scheduleCharacterSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<CharacterSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "g_codus_character_sync",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
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
                val json = PromoCodeSource.fetchJson()
                codesFeed = parseCodesFeed(json)
                val old = prefs.getString("codes_feed", null)
                if (old != json) {
                    prefs.edit().putString("codes_feed", json).apply()
                    runOnUiThread { if (!isFinishing) refreshCurrentScreen() }
                }
            } catch (_: Exception) { }
        }
        executor.scheduleAtFixedRate({
            try {
                val json = PromoCodeSource.fetchJson()
                val old = prefs.getString("codes_feed", null)
                if (old != json) {
                    prefs.edit().putString("codes_feed", json).apply()
                    codesFeed = parseCodesFeed(json)
                    runOnUiThread { if (!isFinishing) refreshCurrentScreen() }
                }
            } catch (_: Exception) { }
        }, 15, 15, TimeUnit.MINUTES)
    }

    private fun loadCachedCodes(): CodesFeed {
        // This is only the last successful network snapshot. Live sources are
        // the source of truth; no bundled promo-code database is used.
        val cached = prefs.getString("codes_feed", null)
        if (!cached.isNullOrBlank()) {
            try { return parseCodesFeed(cached) } catch (_: Exception) { }
        }
        return CodesFeed.empty()
    }

    private fun parseCodesFeed(json: String): CodesFeed {
        val root = JSONObject(json)
        fun read(key: String, expired: Boolean): List<PromoCode> {
            val arr = root.optJSONArray(key) ?: JSONArray()
            return (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                PromoCode(
                    o.optString("game"), o.optString("code"),
                    listOf(o.optString("rewards"), o.optString("reward"), o.optString("items"), o.optString("description"))
                        .firstOrNull { it.isNotBlank() && !it.equals("unknown", true) } ?: "",
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
                "starrail" -> normalized in setOf("starrail", "honkai star rail", "honkai: star rail", "honkai-star-rail")
                "endfield" -> normalized in setOf("endfield", "arknights endfield", "arknights: endfield", "arknights-endfield")
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
        if (currentScreen != Screen.WISHLIST) {
            previousScreen = currentScreen
            previousGameId = currentGameId
        }
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
        animateScreenIn(column)
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
            onlineCharacters
                .filter { it.gameId == meta.id }
                .filterNot { isForbiddenTrackingCharacter(meta.id, it.name, it.id) }
                .filterNot { isMainProtagonist(meta.id, it.id, it.name) }
                .filter { normalizedQuery.isBlank() || it.name.lowercase().contains(normalizedQuery) }
                .forEach { online ->
                    val file = "__online_" + meta.id + "_" + online.id + ".webp"
                    entries += TrackedCharacter(meta.id, meta.name, online.name, file)
                }
        }

        val sorted = entries
            .distinctBy { trackedIdentityKey(it.gameId, it.file) }
            .sortedWith(
                compareByDescending<TrackedCharacter> { isTracked(it.gameId, it.file) }
                    .thenBy { it.name.lowercase() }
            )

        if (sorted.isEmpty()) {
            holder.addView(emptyCard(if (normalizedQuery.isBlank()) "Персонажей пока нет" else "Ничего не найдено"))
            return holder
        }

        var row: LinearLayout? = null
        sorted.forEachIndexed { index, character ->
            if (index % 3 == 0) {
                row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.TOP
                }
                holder.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val cell = trackingCharacterCell(character)
            animateReveal(cell, index)
            row?.addView(cell, LinearLayout.LayoutParams(0, dp(194), 1f).apply {
                marginStart = if (index % 3 == 0) 0 else dp(3)
                marginEnd = dp(3)
                bottomMargin = dp(8)
            })
            if (index == sorted.lastIndex && (index + 1) % 3 != 0) {
                repeat(3 - ((index + 1) % 3)) {
                    row?.addView(Space(this), LinearLayout.LayoutParams(0, dp(194), 1f).apply {
                        marginStart = dp(3)
                        marginEnd = dp(3)
                        bottomMargin = dp(8)
                    })
                }
            }
        }
        return holder
    }

    private fun trackingCharacterCell(character: TrackedCharacter): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedDrawable(surface, 16f)
            clipToOutline = true
        }
        val imageFrame = FrameLayout(this)
        val image = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        view.animate().cancel()
                        view.animate().scaleX(0.88f).scaleY(0.88f).setDuration(90).start()
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        view.animate().cancel()
                        view.animate().scaleX(1f).scaleY(1f).setDuration(140)
                            .setInterpolator(android.view.animation.OvershootInterpolator(1.6f)).start()
                    }
                }
                false
            }
        }
        imageFrame.addView(image, FrameLayout.LayoutParams(-1, dp(136)))
        loadTrackingPortrait(image, character.file, character.gameId)

        val heart = TextView(this).apply {
            text = if (isTracked(character.gameId, character.file)) "❤️" else "🤍"
            textSize = 17f
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(0, 0, 0, 0)
            setTextColor(if (isTracked(character.gameId, character.file)) Color.rgb(255, 91, 123) else Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(175, 10, 11, 16))
                cornerRadius = dp(17).toFloat()
                setStroke(dp(1), Color.argb(120, 255, 255, 255))
            }
            setOnClickListener {
                toggleTracked(character.gameId, character.file)
                text = if (isTracked(character.gameId, character.file)) "❤️" else "🤍"
                setTextColor(if (isTracked(character.gameId, character.file)) Color.rgb(255, 91, 123) else Color.WHITE)
                pulseView(this)
            }
        }
        // The portrait itself has the stronger press effect; keep the heart button tactile too.
        addPressEffect(heart)
        imageFrame.addView(heart, FrameLayout.LayoutParams(dp(34), dp(34), Gravity.TOP or Gravity.END).apply {
            topMargin = dp(8)
            marginEnd = dp(8)
        })
        cell.addView(imageFrame, LinearLayout.LayoutParams(-1, dp(136)))
        val endfieldRarity = if (character.gameId == "endfield") endfieldRarityForDisplay(character.name) else 0
        if (endfieldRarity > 0) {
            cell.addView(label(endfieldRarity.toString() + "★", 11f, Color.rgb(255, 211, 76), true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, 0)
            })
        }
        cell.addView(label(character.name, 11.5f, text, true).apply {
            gravity = Gravity.CENTER
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = true
            setPadding(dp(4), dp(5), dp(4), dp(5))
        }, LinearLayout.LayoutParams(-1, if (endfieldRarity > 0) dp(50) else dp(58)))

        cell.setOnClickListener {
            toggleTracked(character.gameId, character.file)
            heart.text = if (isTracked(character.gameId, character.file)) "❤️" else "🤍"
            heart.setTextColor(if (isTracked(character.gameId, character.file)) Color.rgb(255, 91, 123) else Color.WHITE)
            pulseView(heart)

            // In the wishlist, untracking removes the card immediately with a
            // small iPhone-style uninstall animation: shrink, fade and tilt.
            if (currentScreen == Screen.WISHLIST && !isTracked(character.gameId, character.file)) {
                cell.isClickable = false
                cell.animate().cancel()
                cell.animate()
                    .scaleX(0.08f)
                    .scaleY(0.08f)
                    .alpha(0f)
                    .rotation(-7f)
                    .setDuration(230)
                    .setInterpolator(android.view.animation.AccelerateInterpolator())
                    .withEndAction { refreshCurrentScreen() }
                    .start()
            }
        }
        return cell
    }
    private fun listCharacterFiles(gameId: String): List<String> {
        val folder = gameFolder(gameId) ?: return emptyList()
        return try {
            val files = assets.list(folder).orEmpty()
                .filter { it.endsWith(".webp", true) }
                .filterNot { isMainProtagonist(gameId, it, characterDisplayName(it)) }
                .sorted()
            val prepared = when (gameId) {
                "wuwa" -> (files.filterNot {
                    it == "lucy.webp" || it == "math.webp" || it == "hiyuki.webp"
                } + "__wuwa-lucy.webp" + "__wuwa-aemeath.webp" + "__wuwa-hiyuki.webp").sorted()
                "zzz" -> files.filterNot {
                    it == "lucy.webp" || it == "nicole.webp" || it == "math.webp" || it == "hiyuki.webp"
                }.plus("lucy_alt.webp").distinct().sorted()
                else -> files
            }
            if (gameId == "zzz") {
                // ZZZ aliases are one character each:
                // Yuzuha/Ukinami Yuzuha, Anby/Anby Demara, Billy/Billy Kid,
                // Grace/Grace Howard, Lucy/Lucy_alt, Nicole/Nicole Demara.
                // Keep the requested canonical portrait for each merged identity.
                prepared.distinctBy {
                    normalizeCharacterForMatch(
                        canonicalCharacterDisplayName("zzz", characterDisplayName(it))
                    )
                }
            } else {
                // Dan Heng Imbibitor Lunae and Imbibitor Lunae are the same HSR character.
                // Keep one card, but always use the full display name.
                prepared.distinctBy { normalizeCharacterForMatch(characterDisplayName(it)) }
            }
        } catch (_: Exception) { emptyList() }
    }

    private fun gameFolder(gameId: String): String? = when (gameId) {
        "genshin" -> "genshin"
        "wuwa" -> "wuthering_waves"
        "zzz" -> "zenless_zone_zero"
        "starrail" -> "honkai_star_rail"
        "endfield" -> "arknights_endfield"
        else -> null
    }

    private fun normalizeCharacterForMatch(value: String): String =
        value.lowercase().replace("’", "").replace("'", "").replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "")

    private fun onlineCharacterFor(gameId: String, file: String): OnlineCharacter? {
        if (!file.startsWith("__online_")) return null
        val prefix = "__online_" + gameId + "_"
        if (!file.startsWith(prefix)) return null
        val id = file.removePrefix(prefix).removeSuffix(".webp")
        return onlineCharacters.firstOrNull { it.gameId == gameId && it.id == id }
    }

    private fun cleanCharacterName(value: String): String =
        value.replace("•", " ").replace("·", " ").replace(Regex("\\s+"), " ").trim()

    private fun stripZzzVersion(value: String): String =
        value
            .replace(
                Regex("(?i)(?:\\s*[\\[({_-]?\\s*)3\\.(?:\\d+|x)(?:\\s*[\\])}]?\\s*)"),
                " "
            )
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun canonicalCharacterDisplayName(gameId: String, value: String): String {
        val cleaned = cleanCharacterName(value)
        if (gameId == "zzz") {
            val zzzCleaned = stripZzzVersion(cleaned)
            return when (normalizeCharacterForMatch(zzzCleaned)) {
                "yuzuha", "ukinamiyuzuha" -> "Yuzuha"
                "anby", "anbydemara" -> "Anby"
                "billy", "billykid" -> "Billy Kid"
                "grace", "gracehoward" -> "Grace"
                "lucy", "lucyalt", "lucialt" -> "Lucy"
                "nicole", "nicoledemara" -> "Nicole Demara"
                else -> zzzCleaned
            }
        }
        if (gameId == "starrail") {
            val key = normalizeCharacterForMatch(cleaned)
            if (key == "danhengimbibitorlunae" || key == "imbibitorlunae") {
                return "Dan Heng Imbibitor Lunae"
            }
            if (key == "march7th" || key == "march7" || key == "march7ththehunt" || key == "march7thehunt") {
                return "March 7th"
            }
            if (key == "topaz" || key == "topazandnumby") {
                return "Topaz & Numby"
            }
        }
        return cleaned
    }

    private fun characterDisplayName(file: String): String {
        // CARD_FILENAME_IDENTITY_FIX_V1
        // Ignore technical asset suffixes when resolving the character name.
        val base = file.substringBeforeLast(".")
            .removeSuffix("_card")
            .removeSuffix("_full")
            .replace("•", " ")
            .replace("·", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val normalizedBase = normalizeCharacterForMatch(base)
        if (normalizedBase == "danhengimbibitorlunae" || normalizedBase == "imbibitorlunae") {
            return "Dan Heng Imbibitor Lunae"
        }
        if (normalizedBase == "march7th" || normalizedBase == "march7" || normalizedBase == "march7ththehunt" || normalizedBase == "march7thehunt") {
            return "March 7th"
        }
        if (normalizedBase == "topaz" || normalizedBase == "topazandnumby") {
            return "Topaz & Numby"
        }

        val overrides = mapOf(
            "__wuwa-lucy" to "Lucy",
            "__wuwa-aemeath" to "Aemeath",
            "__wuwa-hiyuki" to "Hiyuki",
            "lucy-alt" to "Lucy",
            "aug" to "Augusta",
            "arataki-itto" to "Arataki Itto",
            "al-haitham" to "Alhaitham",
            "yumemizuki-mizuki" to "Yumemizuki Mizuki",
            "yae-miko" to "Yae Miko",
            "yun-jin" to "Yun Jin",
            "anby-demara-soldier-0" to "Anby: Soldier 0",
            "orhpie-and-magus" to "Orphie & Magus",
            "orhpie-magus" to "Orphie & Magus",
            "luuk-herssen" to "Luuk Herssen",
            "billy-kid" to "Billy Kid",
            "billy" to "Billy Kid",
            "ukinami-yuzuha" to "Yuzuha",
            "anby-demara" to "Anby",
            "grace-howard" to "Grace",
            "blade-mortenax" to "Mortenax Blade"
        )
        overrides[base]?.let { return it }
        return base.split("-").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    private fun endfieldRarityForDisplay(name: String): Int {
        val n = name.lowercase().replace("’", "").replace("'", "").replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "")
        return when {
            n in setOf("arcane","ardelia","camille","ember","endministrator","gilberta","laevatain","lastrite","lifeng","liino","mifu","pogranichnik","rossi","si","tangtang","typhoeus","yvonne","zhuangfangyi") -> 6
            n in setOf("alesh","arclight","avywenna","chenqianyu","dapan","perlica","purrchena","snowshine","wulfgard","xaihi") -> 5
            n in setOf("akekuri","antal","catcher","estella","fluorite") -> 4
            else -> 0
        }
    }

    private fun isForbiddenTrackingCharacter(gameId: String, name: String, file: String = ""): Boolean {
        val nameKey = normalizeCharacterForMatch(name)
        val fileKey = normalizeCharacterForMatch(file.substringBeforeLast("."))
        val forbidden = listOf("storyteller", "thestoryteller", "sunbringer")
        return forbidden.any { key ->
            nameKey == key || nameKey.startsWith(key) ||
                fileKey == key || fileKey.startsWith(key)
        }
    }

    private fun trackedCharacterName(gameId: String, file: String): String {
        if (file.startsWith("__online_")) {
            onlineCharacterFor(gameId, file)?.let { return canonicalCharacterDisplayName(gameId, it.name) }
        }
        return canonicalCharacterDisplayName(gameId, characterDisplayName(file))
    }

    private fun trackedIdentityKey(gameId: String, file: String): String =
        "tracked_v2_" + gameId + "_" + normalizeCharacterForMatch(trackedCharacterName(gameId, file))

    private fun canonicalPreferredTrackingFile(gameId: String, file: String): String {
        if (gameId != "zzz" || file.startsWith("__online_")) return file

        return when (normalizeCharacterForMatch(trackedCharacterName(gameId, file))) {
            "yuzuha" -> "ukinami-yuzuha.webp"
            "anby" -> "anby-demara.webp"
            "billykid" -> "billy-kid.webp"
            "grace" -> "grace-howard.webp"
            "lucy" -> "lucy_alt.webp"
            "nicoledemara" -> "nicole-demara.webp"
            else -> file
        }
    }

    private fun trackingKey(gameId: String, file: String) = trackedIdentityKey(gameId, file)

    private fun migrateTrackingKeys() {
        val editor = prefs.edit()
        var changed = false

        prefs.all.keys.filter { key ->
            val normalized = key.lowercase().replace("’", "").replace("'", "").replace(Regex("[^a-z0-9]+"), "")
            normalized.contains("trackedv2endfieldthestoryteller") ||
                normalized.contains("trackedv2endfieldstoryteller") ||
                normalized.contains("trackedv2endfieldsunbringer") ||
                normalized.contains("trackedendfieldthestoryteller") ||
                normalized.contains("trackedendfieldstoryteller") ||
                normalized.contains("trackedendfieldsunbringer")
        }.forEach { editor.remove(it); changed = true }

        prefs.all.keys.filter { key ->
            val normalized = key.lowercase().replace("’", "").replace("'", "").replace(Regex("[^a-z0-9]+"), "")
            normalized.contains("trackedv2endfieldthestoryteller") ||
                normalized.contains("trackedv2endfieldstoryteller") ||
                normalized.contains("trackedv2endfieldsunbringer") ||
                normalized.contains("trackedendfieldthestoryteller") ||
                normalized.contains("trackedendfieldstoryteller") ||
                normalized.contains("trackedendfieldsunbringer")
        }.forEach { editor.remove(it); changed = true }

        // Storyteller and Sunbringer are permanently excluded from Tracking/Wishlist.
        // Remove all legacy/new saved keys for either name, regardless of game/prefix/version.
        prefs.all.keys.filter { key ->
            val normalized = key.lowercase()
                .replace("’", "")
                .replace("'", "")
                .replace(Regex("[^a-z0-9]+"), "")
            normalized.contains("storyteller") || normalized.contains("sunbringer")
        }.forEach {
            editor.remove(it)
            changed = true
        }

        prefs.all.forEach { (key, value) ->
            val enabled = value as? Boolean ?: return@forEach

            if (key.startsWith("tracked_v2_")) {
                val remainder = key.removePrefix("tracked_v2_")
                val separator = remainder.indexOf('_')
                if (separator <= 0 || separator >= remainder.lastIndex) return@forEach

                val gameId = remainder.substring(0, separator)
                val identity = remainder.substring(separator + 1)
                if (gameId == "zzz") {
                    val identityWithoutVersion = identity.replace(
                        Regex("(?i)3x$|3\\d+$"),
                        ""
                    )
                    val canonicalIdentity = normalizeCharacterForMatch(
                        canonicalCharacterDisplayName("zzz", identityWithoutVersion)
                    )
                    val canonicalKey = "tracked_v2_zzz_" + canonicalIdentity
                    if (canonicalKey != key) {
                        if (enabled) editor.putBoolean(canonicalKey, true)
                        editor.remove(key)
                        changed = true
                    }
                }
                return@forEach
            }

            if (!key.startsWith("tracked_")) return@forEach

            val remainder = key.removePrefix("tracked_")
            val separator = remainder.indexOf('_')
            if (separator <= 0 || separator >= remainder.lastIndex) return@forEach

            val gameId = remainder.substring(0, separator)
            val file = remainder.substring(separator + 1)
            if (enabled) {
                editor.putBoolean(trackingKey(gameId, file), true)
            }
            editor.remove(key)
            changed = true
        }

        if (changed) editor.apply()
    }

    private fun isTracked(gameId: String, file: String): Boolean =
        prefs.getBoolean(trackingKey(gameId, file), false)

    private fun toggleTracked(gameId: String, file: String) {
        val enabled = !isTracked(gameId, file)
        prefs.edit().putBoolean(trackingKey(gameId, file), enabled).apply()
        if (enabled) {
            val request = OneTimeWorkRequestBuilder<NotificationSyncWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(this).enqueue(request)
        } else {
            // Reset per-character notification state so re-adding a character
            // to the Wish List can notify again for a later/active banner.
            getSharedPreferences("g_codus_notifications", Context.MODE_PRIVATE).edit()
                .remove("appearance_" + gameId + "_" + file)
                .remove("wishlist_next_" + gameId + "_" + file)
                .remove("date_" + gameId + "_" + file)
                .remove("ending_" + gameId + "_" + file)
                .apply()
        }
    }

    private fun loadTrackingPortrait(image: ImageView, file: String, gameId: String) {
        val folder = gameFolder(gameId) ?: return
        image.setImageDrawable(null)

        val online = onlineCharacterFor(gameId, file)
        if (online != null) {
            loadDatabasePortrait(image, online)
            return
        }

        // Some supplied portraits have filenames inherited from an earlier
        // mapping. Resolve those aliases to the exact user-supplied local assets.
        val specialAssetPath = when (file) {
            "__wuwa-lucy.webp" -> "zenless_zone_zero/lucy.webp"
            "__wuwa-aemeath.webp" -> "zenless_zone_zero/math.webp"
            "__wuwa-hiyuki.webp" -> "zenless_zone_zero/hiyuki.webp"
            else -> null
        }
        if (specialAssetPath != null) {
            try {
                assets.open(specialAssetPath).use { input ->
                    val bitmap = android.graphics.BitmapFactory.decodeStream(input)
                    if (bitmap != null) image.setImageBitmap(bitmap)
                }
            } catch (_: Exception) { }
            return
        }

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

    private fun refreshCharacterDatabaseInBackground() {
        executor.execute {
            try {
                val fresh = CharacterDatabase.fetch(this@MainActivity)
                if (fresh.isNotEmpty()) {
                    onlineCharacters = fresh
                    runOnUiThread {
                        if (!isFinishing) refreshCurrentScreen()
                    }
                }
            } catch (_: Exception) { }
        }
        executor.scheduleAtFixedRate({
            try {
                val fresh = CharacterDatabase.fetch(this@MainActivity)
                if (fresh.isNotEmpty()) {
                    onlineCharacters = fresh
                    runOnUiThread {
                        if (!isFinishing && currentScreen != Screen.GAME) refreshCurrentScreen()
                    }
                }
            } catch (_: Exception) { }
        }, 15, 15, TimeUnit.MINUTES)
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
        if (currentScreen != Screen.TRACKING) {
            previousScreen = currentScreen
            previousGameId = currentGameId
        }
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
        addPressEffect(back)
        header.addView(back, LinearLayout.LayoutParams(dp(42), dp(50)))
        header.addView(label(gameMeta.first { it.id == gameId }.name, 24f, text, true), LinearLayout.LayoutParams(0, -2, 1f))
        column.addView(header)
        column.addView(trackingSection(gameId))
        scroll.addView(column)
        root.addView(scroll)
        animateScreenIn(column)
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
        image.setImageDrawable(null)

        // Character identity and portrait URL come exclusively from the
        // G-Codus character database. No Prydwen or game API is queried.
        val normalized = normalizeCharacterForMatch(character)
        val online = onlineCharacters.firstOrNull {
            it.gameId == gameId &&
                (normalizeCharacterForMatch(it.name) == normalized ||
                 normalizeCharacterForMatch(it.id) == normalized)
        }

        if (online != null) {
            loadDatabasePortrait(image, online)
            return
        }

        // Existing bundled portraits are a local fallback only.
        loadBundledPortrait(image, character, gameId)
    }

    private fun loadDatabasePortrait(image: ImageView, character: OnlineCharacter) {
        val url = character.portraitUrl.ifBlank { CharacterDatabase.imageUrl(character.id) }
        if (url.isBlank()) return

        val cacheKey = "db:" + character.id
        portraitCache.get(cacheKey)?.let {
            image.setImageBitmap(it)
            return
        }

        imageExecutor.execute {
            try {
                val connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 8000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "G-Codus/1.0")
                val bitmap = connection.inputStream.use {
                    android.graphics.BitmapFactory.decodeStream(it)
                }
                connection.disconnect()

                if (bitmap != null) {
                    portraitCache.put(cacheKey, bitmap)
                    runOnUiThread {
                        if (!isFinishing && image.isAttachedToWindow) image.setImageBitmap(bitmap)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    private fun loadBundledPortrait(image: ImageView, character: String, gameId: String) {
        val localizedAlias = when (character.trim()) {
            "卜灵", "卜靈" -> "Buling"
            "桃祈" -> "Taoqi"
            "釉瑚" -> "Youhu"
            "灯灯", "燈燈" -> "Lumi"
            "丹瑾" -> "Danjin"
            "炽霞", "熾霞" -> "Chixia"
            else -> character
        }

        val normalized = localizedAlias.lowercase()
            .replace("’", "")
            .replace("'", "")
            .replace(":", "")
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val gameFolder = gameFolder(gameId) ?: return

        try {
            val files = assets.list(gameFolder)?.toList().orEmpty()
            val target = files.firstOrNull { file ->
                val stem = file.substringBeforeLast('.')
                    .removeSuffix("_card")
                    .removeSuffix("_full")
                    .lowercase()
                    .replace("’", "")
                    .replace("'", "")
                    .replace(":", "")
                    .replace("&", "and")
                    .replace("•", "-")
                    .replace("·", "-")
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')

                stem.replace("-", "") == normalized.replace("-", "")
            } ?: return

            assets.open("$gameFolder/$target").use { input ->
                android.graphics.BitmapFactory.decodeStream(input)?.let {
                    image.setImageBitmap(it)
                }
            }
        } catch (_: Exception) { }
    }

    private fun isMainProtagonist(gameId: String, file: String, name: String): Boolean {
        val n = normalizeCharacterForMatch(name)
        val f = normalizeCharacterForMatch(file.substringBeforeLast("."))
        return when (gameId) {
            "genshin" -> n in setOf("traveler", "traveller", "aether", "lumine") ||
                f in setOf("traveler", "traveller", "aether", "lumine")
            "wuwa" -> n == "rover" || f == "rover"
            "zzz" -> n in setOf("belle", "wise", "proxy") || f in setOf("belle", "wise", "proxy")
            else -> false
        }
    }

    private fun sameCharacterIdentity(gameId: String, localName: String, onlineName: String, onlineSlug: String, localFile: String): Boolean {
        val a = normalizeCharacterForMatch(localName)
        val b = normalizeCharacterForMatch(onlineName)
        val slug = normalizeCharacterForMatch(onlineSlug)
        val file = normalizeCharacterForMatch(localFile.substringBeforeLast("."))
        if (gameId == "starrail") {
            fun hsrCanonical(value: String): String = when (value) {
                "mortenaxblade", "blademortenax" -> "mortenaxblade"
                else -> value
            }
            if (hsrCanonical(a) == hsrCanonical(b) || hsrCanonical(file) == hsrCanonical(slug)) return true
        }
        return when (gameId) {
            "zzz" -> normalizeCharacterForMatch(localName) == normalizeCharacterForMatch(onlineName) ||
                normalizeCharacterForMatch(localFile) == normalizeCharacterForMatch(onlineSlug)
            else -> normalizeCharacterForMatch(localName) == normalizeCharacterForMatch(onlineName) ||
                normalizeCharacterForMatch(localFile) == normalizeCharacterForMatch(onlineSlug)
        }
    }

    private fun startCountdownTicker() {
        countdownExecutor.scheduleAtFixedRate({
            runOnUiThread {
                val now = Instant.now()
                countdownViews.forEach { pair ->
                    try {
                        val target = parseBannerInstant(pair.second, endOfDay = true) ?: return@forEach
                        val seconds = Duration.between(now, target).seconds.coerceAtLeast(0)
                        pair.first.text = "До окончания\n" + formatCountdown(seconds)
                    } catch (_: Exception) { }
                }
            }
        }, 0, 1, TimeUnit.SECONDS)
    }

    private fun parseBannerInstant(value: String, endOfDay: Boolean = false): Instant? {
        val v = value.trim()
        if (v.isBlank() || v == "null") return null
        return try {
            OffsetDateTime.parse(v).toInstant()
        } catch (_: Exception) {
            try {
                Instant.parse(v)
            } catch (_: Exception) {
                try {
                    java.time.LocalDateTime.parse(v)
                        .atZone(ZoneId.systemDefault()).toInstant()
                } catch (_: Exception) {
                    try {
                        val date = java.time.LocalDate.parse(v)
                        val time = if (endOfDay) java.time.LocalTime.of(23, 59, 59)
                        else java.time.LocalTime.MIDNIGHT
                        date.atTime(time).atZone(ZoneId.systemDefault()).toInstant()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }

    // Banner source of truth: only the current G-Codus GitHub database.
    private fun loadFeed(): List<GameFeed> {
        val source = bannerFeedJson ?: loadCachedBannerFeed()?.also { bannerFeedJson = it }
            ?: return emptyList()

        return try {
            val root = JSONObject(source)
            val gamesValue = root.opt("games")
            val result = mutableListOf<GameFeed>()
            val definitions = listOf(
                "genshin" to "Genshin Impact",
                "wuwa" to "Wuthering Waves",
                "zzz" to "Zenless Zone Zero",
                "starrail" to "Honkai: Star Rail",
                "endfield" to "Arknights: Endfield"
            )

            for ((wantedId, wantedName) in definitions) {
                val g = when (gamesValue) {
                    is JSONObject -> {
                        gamesValue.optJSONObject(wantedName)
                            ?: gamesValue.optJSONObject(wantedId)
                    }
                    is JSONArray -> {
                        var found: JSONObject? = null
                        for (i in 0 until gamesValue.length()) {
                            val candidate = gamesValue.optJSONObject(i) ?: continue
                            val id = candidate.optString("id")
                            val name = candidate.optString("name")
                            if (id.equals(wantedId, true) || name.equals(wantedName, true)) {
                                found = candidate
                                break
                            }
                        }
                        found
                    }
                    else -> null
                } ?: continue

                val id = g.optString("id").ifBlank { wantedId }
                val name = g.optString("name").ifBlank { wantedName }

                result += try {
                    GameFeed(
                        id,
                        name,
                        parseBanners(g, "current"),
                        parseBanners(g, "next"),
                        parseBanners(g, "upcoming")
                    )
                } catch (_: Exception) {
                    GameFeed(id, name, emptyList(), emptyList(), emptyList())
                }
            }

            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun refreshBannerFeedInBackground() {
        fun refreshOnce() {
            try {
                // Only G-Codus GitHub database. Never use bundled/old local banner data.
                val fresh = BannerSource.fetchNormalized(this@MainActivity)
                val games = JSONObject(fresh).getJSONArray("games")
                if (games.length() == 0) return
                bannerFeedJson = fresh
                prefs.edit().putString("banner_feed", fresh).apply()
                runOnUiThread {
                    if (!isFinishing) refreshCurrentScreen()
                }
            } catch (_: Exception) {
                bannerFeedJson = null
            }
        }
        executor.execute { refreshOnce() }
        executor.scheduleAtFixedRate({ refreshOnce() }, 15, 15, TimeUnit.MINUTES)
    }

    private fun parseBanners(game: JSONObject, key: String): List<Banner> {
        val raw = game.opt(key)
        val phases = when (raw) {
            is JSONObject -> listOf(raw)
            is JSONArray -> (0 until raw.length()).mapNotNull { raw.optJSONObject(it) }
            else -> emptyList()
        }
        if (phases.isEmpty()) return emptyList()

        val result = mutableListOf<Banner>()
        for (b in phases) {
            val arr = b.optJSONArray("five_star")
                ?: b.optJSONArray("characters")
                ?: JSONArray()
            val fourStarArr = b.optJSONArray("four_star")
                ?: b.optJSONArray("fourStars")
                ?: JSONArray()

            fun canonicalBannerCharacterName(value: String): String {
                val cleaned = value.replace("•", " ").replace("·", " ").replace(Regex("\\s+"), " ").trim()
                val key = normalizeCharacterForMatch(cleaned)
                return if (game.optString("id").equals("starrail", true) &&
                    key in setOf("danhengimbibitorlunae", "imbibitorlunae")) {
                    "Dan Heng Imbibitor Lunae"
                } else {
                    cleaned
                }
            }

            val fiveStars = (0 until arr.length())
                .map { canonicalBannerCharacterName(arr.optString(it)) }
                .filter { it.isNotBlank() }
                .distinctBy { normalizeCharacterForMatch(it) }

            val fourStars = (0 until fourStarArr.length())
                .map { fourStarArr.optString(it) }
                .filter { it.isNotBlank() }
                .distinct()
                .take(3)

            if (fiveStars.isEmpty()) continue

            // Each featured 5★ is its own swipeable banner card.
            for (five in fiveStars) {
                result += Banner(
                    game.optString("id"),
                    game.optString("name"),
                    b.optString("version", b.optString("phase")),
                    b.optString("start").takeIf { it.isNotBlank() && it != "null" },
                    b.optString("end").takeIf { it.isNotBlank() && it != "null" },
                    listOf(five),
                    fourStars,
                    key == "next",
                    b.optBoolean("unconfirmed", false)
                )
            }
        }
        return result.distinctBy {
            it.gameId + "|" + it.version + "|" + it.characters.joinToString("|")
        }
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

    private fun formatDate(value: String): String {
        val zone = ZoneId.systemDefault()
        try {
            return OffsetDateTime.parse(value)
                .atZoneSameInstant(zone)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy • HH:mm"))
        } catch (_: Exception) { }
        try {
            return java.time.LocalDateTime.parse(value)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy • HH:mm"))
        } catch (_: Exception) { }
        try {
            return java.time.LocalDate.parse(value)
                .format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
        } catch (_: Exception) { }
        return value
    }


    private fun gameAccent(id: String): Int = when (id) {
        "genshin" -> Color.rgb(155, 114, 255)
        "wuwa" -> Color.rgb(79, 168, 255)
        "zzz" -> Color.rgb(240, 182, 77)
        "starrail" -> Color.rgb(91, 173, 255)
        "endfield" -> Color.rgb(174, 182, 197)
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

    private fun animateScreenIn(container: android.view.ViewGroup) {
        for (index in 0 until container.childCount) {
            val child = container.getChildAt(index)
            // Dynamic game-page content owns its own transition. Animating the
            // same View here would cancel animatePageChange() before it builds.
            if (child.tag == "dynamic_page_content") continue
            if (child.visibility != View.VISIBLE) continue
            child.animate().cancel()
            child.alpha = 0f
            child.translationY = dp(16).toFloat()
            child.scaleX = 0.985f
            child.scaleY = 0.985f
            child.animate()
                .alpha(1f)
                .translationY(0f)
                .scaleX(1f)
                .scaleY(1f)
                .setStartDelay((index * 45L).coerceAtMost(260L))
                .setDuration(260)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun animateReveal(view: View, index: Int) {
        view.animate().cancel()
        view.alpha = 0f
        view.scaleX = 0.94f
        view.scaleY = 0.94f
        view.translationY = dp(10).toFloat()
        view.post {
            view.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .translationY(0f)
                .setStartDelay((index % 12) * 28L)
                .setDuration(300)
                .setInterpolator(android.view.animation.OvershootInterpolator(1.1f))
                .start()
        }
    }

    private fun pulseView(view: View) {
        view.animate().cancel()
        view.animate()
            .scaleX(1.16f)
            .scaleY(1.16f)
            .setDuration(90)
            .setInterpolator(android.view.animation.DecelerateInterpolator())
            .withEndAction {
                view.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(180)
                    .setInterpolator(android.view.animation.OvershootInterpolator(1.6f))
                    .start()
            }
            .start()
    }

    private fun addPressEffect(view: View) {
        view.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(0.92f)
                        .scaleY(0.92f)
                        .translationY(dp(2).toFloat())
                        .setDuration(75)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .translationY(0f)
                        .setDuration(150)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                        .withEndAction { v.performClick() }
                        .start()
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    v.animate().cancel()
                    v.animate()
                        .scaleX(1f)
                        .scaleY(1f)
                        .setDuration(150)
                        .setInterpolator(android.view.animation.OvershootInterpolator(1.5f))
                        .start()
                    true
                }
                else -> true
            }
        }
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
        val next: List<Banner>,
        val upcoming: List<Banner> = emptyList()
    )

    data class GameMeta(
        val id: String,
        val name: String,
        val resourceName: String
    )
}
