from pathlib import Path

p = Path('app/src/main/java/com/example/gcodus/MainActivity.kt')
s = p.read_text()

if 'import android.Manifest' not in s:
    s = s.replace('import android.content.Context\n', 'import android.Manifest\nimport android.content.Context\n')
if 'import androidx.core.app.ActivityCompat' not in s:
    s = s.replace('import androidx.appcompat.app.AppCompatActivity\n', 'import androidx.appcompat.app.AppCompatActivity\nimport androidx.core.app.ActivityCompat\nimport androidx.core.content.ContextCompat\n')

old = '''        codesFeed = loadCachedCodes()\n        scheduleCodeSync()\n        refreshCodesInBackground()\n        showHome()'''
new = '''        codesFeed = loadCachedCodes()\n        requestNotificationPermission()\n        scheduleCodeSync()\n        scheduleNotificationSync()\n        refreshCodesInBackground()\n        showHome()'''
if old in s and 'requestNotificationPermission()' not in s:
    s = s.replace(old, new, 1)

old = '''        column.addView(sectionLabel("БАННЕРЫ СЕЙЧАС"))\n        column.addView(bannerPager(game.current, false, game.id))'''
new = '''        column.addView(sectionLabel("ИНФОРМАЦИЯ О БАННЕРАХ"))\n        column.addView(sectionLabel("БАННЕРЫ СЕЙЧАС").apply {\n            setPadding(dp(4), dp(4), 0, dp(6))\n        })\n        column.addView(bannerPager(game.current, false, game.id))'''
s = s.replace(old, new, 1)

old = '''        column.addView(sectionLabel("ПРОМОКОДЫ").apply {\n            setPadding(0, dp(26), 0, dp(8))\n        })\n        column.addView(codeSection(game.id))'''
new = '''        column.addView(sectionLabel("ПРОМОКОДЫ").apply {\n            setPadding(0, dp(28), 0, dp(8))\n        })\n        column.addView(codeSection(game.id))'''
s = s.replace(old, new, 1)

old = '''    private fun codeSection(gameId: String): View {\n        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }\n        val active = codesFeed.active.filter { it.game == gameId }\n        val expired = codesFeed.expired.filter { it.game == gameId }\n\n        if (active.isEmpty()) box.addView(emptyCard("Действительных промокодов сейчас нет"))\n        active.forEach { box.addView(codeCard(it)) }\n\n        if (expired.isNotEmpty()) {\n            box.addView(sectionLabel("ПРОСРОЧЕННЫЕ").apply {\n                setPadding(dp(4), dp(18), 0, dp(8))\n            })\n            expired.take(20).forEach { box.addView(codeCard(it)) }\n        }\n        return box\n    }'''
new = '''    private fun codeSection(gameId: String): View {\n        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }\n        val active = codesFeed.active.filter { it.game == gameId }\n        val expired = codesFeed.expired.filter { it.game == gameId }\n\n        box.addView(sectionLabel("ДЕЙСТВУЮЩИЕ").apply {\n            setPadding(dp(4), dp(4), 0, dp(8))\n        })\n        if (active.isEmpty()) box.addView(emptyCard("Действующих промокодов сейчас нет"))\n        active.forEach { box.addView(codeCard(it)) }\n\n        box.addView(sectionLabel("ПРОСРОЧЕННЫЕ").apply {\n            setPadding(dp(4), dp(22), 0, dp(8))\n        })\n        if (expired.isEmpty()) box.addView(emptyCard("Просроченных промокодов нет"))\n        expired.take(20).forEach { box.addView(codeCard(it)) }\n        return box\n    }'''
if old in s:
    s = s.replace(old, new, 1)

marker = '    private fun refreshCodesInBackground() {'
methods = '''    private fun requestNotificationPermission() {\n        if (android.os.Build.VERSION.SDK_INT >= 33 &&\n            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=\n            android.content.pm.PackageManager.PERMISSION_GRANTED) {\n            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7001)\n        }\n    }\n\n    private fun scheduleNotificationSync() {\n        val constraints = Constraints.Builder()\n            .setRequiredNetworkType(NetworkType.CONNECTED)\n            .build()\n        val request = PeriodicWorkRequestBuilder<NotificationSyncWorker>(15, TimeUnit.MINUTES)\n            .setConstraints(constraints)\n            .build()\n        WorkManager.getInstance(this).enqueueUniquePeriodicWork(\n            "g_codus_notifications",\n            ExistingPeriodicWorkPolicy.KEEP,\n            request\n        )\n    }\n\n'''
if marker in s and 'private fun scheduleNotificationSync()' not in s:
    s = s.replace(marker, methods + marker, 1)

p.write_text(s)
print('Notification/UI patch applied')
