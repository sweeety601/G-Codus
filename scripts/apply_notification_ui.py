from pathlib import Path

p = Path('app/src/main/java/com/example/gcodus/MainActivity.kt')
s = p.read_text()

if 'import android.Manifest' not in s:
    s = s.replace('import android.content.Context\n', 'import android.Manifest\nimport android.content.Context\n')
if 'import androidx.core.app.ActivityCompat' not in s:
    s = s.replace('import androidx.appcompat.app.AppCompatActivity\n', 'import androidx.appcompat.app.AppCompatActivity\nimport androidx.core.app.ActivityCompat\nimport androidx.core.content.ContextCompat\n')

# Keep the notification scheduling/permission changes idempotent.
old = '''        codesFeed = loadCachedCodes()\n        scheduleCodeSync()\n        refreshCodesInBackground()\n        showHome()'''
new = '''        codesFeed = loadCachedCodes()\n        requestNotificationPermission()\n        scheduleCodeSync()\n        scheduleNotificationSync()\n        refreshCodesInBackground()\n        showHome()'''
if old in s:
    s = s.replace(old, new, 1)

# Replace the old single long game screen with two real pages:
# 1) banner information, 2) promo codes. Favorites remain banner-only.
start = s.find('    private fun showGame(gameId: String) {')
end = s.find('    private fun makeTopGameBar(): View {', start)
if start != -1 and end != -1:
    new_show_game = r'''    private fun showGame(gameId: String) {
        countdownViews.clear()
        val game = loadFeed().firstOrNull { it.id == gameId } ?: return
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
        pageButtons.addView(bannerButton, LinearLayout.LayoutParams(0, -2, 1f))
        pageButtons.addView(codeButton, LinearLayout.LayoutParams(0, -2, 1f))
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
            content.removeAllViews()
            selectedButton(bannerButton, false)
            selectedButton(codeButton, true)
            content.addView(sectionLabel("ПРОМОКОДЫ"))
            content.addView(codeSection(game.id))
        }

        bannerButton.setOnClickListener { showBannerPage() }
        codeButton.setOnClickListener { showCodePage() }
        showBannerPage()

        scroll.addView(column)
        root.addView(scroll)
    }

'''
    s = s[:start] + new_show_game + s[end:]

# Ensure the promo-code page always has two explicit groups.
old_codes = '''    private fun codeSection(gameId: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val active = codesFeed.active.filter { it.game == gameId }
        val expired = codesFeed.expired.filter { it.game == gameId }

        if (active.isEmpty()) box.addView(emptyCard("Действительных промокодов сейчас нет"))
        active.forEach { box.addView(codeCard(it)) }

        if (expired.isNotEmpty()) {
            box.addView(sectionLabel("ПРОСРОЧЕННЫЕ").apply {
                setPadding(dp(4), dp(18), 0, dp(8))
            })
            expired.take(20).forEach { box.addView(codeCard(it)) }
        }
        return box
    }'''
new_codes = '''    private fun codeSection(gameId: String): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val active = codesFeed.active.filter { it.game == gameId }
        val expired = codesFeed.expired.filter { it.game == gameId }

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
    }'''
if old_codes in s:
    s = s.replace(old_codes, new_codes, 1)

marker = '    private fun refreshCodesInBackground() {'
methods = '''    private fun requestNotificationPermission() {
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

'''
if marker in s and 'private fun scheduleNotificationSync()' not in s:
    s = s.replace(marker, methods + marker, 1)

p.write_text(s)
print('Game pages and notification scheduling patch applied')
