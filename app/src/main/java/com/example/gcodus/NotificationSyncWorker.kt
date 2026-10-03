package com.example.gcodus

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

class NotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val CHANNEL_ID = "g_codus_updates"
        private const val PREFS = "g_codus_notifications"
        private const val BANNER_URL = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/app/src/main/assets/banner_feed.json"
        private const val CODES_URL = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/app/src/main/assets/codes_feed.json"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_BANNER_PREFIX = "banner_"
        private const val KEY_CODES_PREFIX = "codes_"
    }

    override fun doWork(): Result {
        if (BuildConfig.VERSION_CODE < 1) return Result.success()
        if (ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED && android.os.Build.VERSION.SDK_INT >= 33) return Result.success()

        createChannel()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        return try {
            val bannerJson = fetch(BANNER_URL)
            val codeJson = fetch(CODES_URL)
            val initialized = prefs.getBoolean(KEY_INITIALIZED, false)

            if (initialized) {
                notifyBannerPhaseChanges(prefs, bannerJson)
                notifyNewCodes(prefs, codeJson)
            }

            saveBannerSnapshots(prefs, bannerJson)
            saveCodeSnapshots(prefs, codeJson)
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notifyBannerPhaseChanges(prefs: android.content.SharedPreferences, json: String) {
        val games = JSONObject(json).optJSONArray("games") ?: JSONArray()
        for (i in 0 until games.length()) {
            val game = games.getJSONObject(i)
            val id = game.optString("id")
            if (!isFavorite(id)) continue
            val current = game.optJSONObject("current") ?: continue
            val chars = current.optJSONArray("five_star") ?: JSONArray()
            val signature = (0 until chars.length()).joinToString("|") { chars.optString(it) }
            val key = KEY_BANNER_PREFIX + id
            val old = prefs.getString(key, null)
            if (old != null && old != signature) {
                val names = (0 until chars.length()).joinToString("\n") { "⭐" + chars.optString(it) }
                showNotification(
                    id.hashCode() and 0x7fffffff,
                    "${game.optString("name")}: СМЕНА ФАЗЫ",
                    names
                )
            }
        }
    }

    private fun notifyNewCodes(prefs: android.content.SharedPreferences, json: String) {
        val active = JSONObject(json).optJSONArray("active") ?: JSONArray()
        val byGame = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until active.length()) {
            val item = active.getJSONObject(i)
            val game = item.optString("game")
            if (isFavorite(game)) byGame.getOrPut(game) { mutableListOf() }.add(item.optString("code"))
        }

        for ((game, codes) in byGame) {
            val signature = codes.sorted().joinToString("|")
            val key = KEY_CODES_PREFIX + game
            val old = prefs.getString(key, null)
            if (old != null) {
                val oldSet = old.split("|").filter { it.isNotBlank() }.toSet()
                val newCodes = codes.filter { it !in oldSet }.distinct()
                if (newCodes.isNotEmpty()) {
                    val title = gameName(game) + ": Новые промокоды"
                    val body = newCodes.joinToString("\n") { "💫$it" }
                    showNotification((game.hashCode() * 31 + 2) and 0x7fffffff, title, body)
                }
            }
        }
    }

    private fun saveBannerSnapshots(prefs: android.content.SharedPreferences, json: String) {
        val games = JSONObject(json).optJSONArray("games") ?: JSONArray()
        val editor = prefs.edit()
        for (i in 0 until games.length()) {
            val game = games.getJSONObject(i)
            val chars = game.optJSONObject("current")?.optJSONArray("five_star") ?: JSONArray()
            val signature = (0 until chars.length()).joinToString("|") { chars.optString(it) }
            editor.putString(KEY_BANNER_PREFIX + game.optString("id"), signature)
        }
        editor.apply()
    }

    private fun saveCodeSnapshots(prefs: android.content.SharedPreferences, json: String) {
        val active = JSONObject(json).optJSONArray("active") ?: JSONArray()
        val byGame = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until active.length()) {
            val item = active.getJSONObject(i)
            byGame.getOrPut(item.optString("game")) { mutableListOf() }.add(item.optString("code"))
        }
        val editor = prefs.edit()
        byGame.forEach { (game, codes) ->
            editor.putString(KEY_CODES_PREFIX + game, codes.distinct().sorted().joinToString("|"))
        }
        editor.apply()
    }

    private fun isFavorite(gameId: String): Boolean =
        applicationContext.getSharedPreferences("g_codus", Context.MODE_PRIVATE)
            .getBoolean("favorite_$gameId", false)

    private fun gameName(id: String) = when (id) {
        "genshin" -> "Genshin Impact"
        "wuwa" -> "Wuthering Waves"
        "zzz" -> "Zenless Zone Zero"
        else -> id
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "G-Codus/1.0")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "G-Codus: обновления", NotificationManager.IMPORTANCE_HIGH)
            )
        }
    }

    private fun showNotification(id: Int, title: String, body: String) {
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = PendingIntent.getActivity(
            applicationContext, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body.replace("\n", " • "))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }
}
