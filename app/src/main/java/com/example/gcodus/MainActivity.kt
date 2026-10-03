package com.example.gcodus

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
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
    private val executor = Executors.newSingleThreadScheduledExecutor()
    private val countdownViews = mutableListOf<Pair<TextView, String>>()
    private val prefs by lazy { getSharedPreferences("g_codus", Context.MODE_PRIVATE) }

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
        setContentView(R.layout.activity_main)
        showHome()
        startCountdownTicker()
    }

    private fun showHome() {
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
        countdownViews.clear()
        val game = loadFeed().firstOrNull { it.id == gameId } ?: return
        val root = findViewById<FrameLayout>(R.id.root)
        root.removeAllViews()

        val scroll = makeScroll()
        val column = makeColumn()

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(14))
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

        val title = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        title.addView(label(game.name, 22f, text, true))
        title.addView(label("Баннеры", 13f, muted, false).apply {
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

        column.addView(sectionLabel("БАННЕРЫ СЕЙЧАС"))
        column.addView(bannerPager(game.current, false, game.id))

        column.addView(sectionLabel("СЛЕДУЮЩИЕ БАННЕРЫ").apply {
            setPadding(0, dp(22), 0, dp(8))
        })
        column.addView(bannerPager(game.next, true, game.id))

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
                when (meta.id) {
                    "genshin" -> "Genshin"
                    "wuwa" -> "WuWa"
                    else -> "ZZZ"
                }, 11f, muted, true).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(5), 0, 0)
            })

            icons.addView(item, LinearLayout.LayoutParams(0, dp(94), 1f))
        }

        wrapper.addView(icons)
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
        val slug = character.lowercase()
            .replace("’", "")
            .replace("'", "")
            .replace(":", "")
            .replace("&", "and")
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')

        val localName = when (gameId) {
            "genshin" -> "banner_genshin_" + slug.replace("-", "_")
            "wuwa" -> "banner_wuthering_waves_" + slug.replace("-", "_")
            "zzz" -> "banner_zenless_zone_zero_" + slug.replace("-", "_")
            else -> ""
        }

        val localId = if (localName.isNotEmpty()) resources.getIdentifier(
            localName, "drawable", packageName
        ) else 0

        if (localId != 0) {
            image.setImageResource(localId)
            return
        }

        val url = "https://cdn.prydwen.gg/images/" +
            when (gameId) {
                "genshin" -> "genshin-impact"
                "wuwa" -> "wuthering-waves"
                "zzz" -> "zenless-zone-zero"
                else -> gameId
            } + "/characters/" + slug + "_full.webp"

        executor.execute {
            try {
                val connection = java.net.URL(url).openConnection()
                connection.connectTimeout = 12000
                connection.readTimeout = 20000
                connection.setRequestProperty("User-Agent", "G-Codus/1.0")
                val bitmap = android.graphics.BitmapFactory.decodeStream(connection.getInputStream())
                if (bitmap != null) runOnUiThread { image.setImageBitmap(bitmap) }
            } catch (_: Exception) { }
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
        setPadding(dp(16), dp(18), dp(16), dp(32))
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
