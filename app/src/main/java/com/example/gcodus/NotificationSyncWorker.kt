package com.example.gcodus

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

class NotificationSyncWorker(appContext: Context, workerParams: WorkerParameters) : Worker(appContext, workerParams) {
    companion object {
        private const val CHANNEL_ID = "g_codus_updates"
        private const val PREFS = "g_codus_notifications"
        private const val APP_PREFS = "g_codus"
        private const val CODES_URL = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/app/src/main/assets/codes_feed.json"
        private const val INITIALIZED = "initialized"
    }

    override fun doWork(): Result {
        if (BuildVersion.requiresNotificationPermission &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return Result.success()
        }
        createChannel()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val appPrefs = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        return try {
            val bannerJson = BannerSource.fetchNormalized(applicationContext)
            val codeJson = fetch(CODES_URL)
            appPrefs.edit().putString("banner_feed", bannerJson).apply()
            val initialized = prefs.getBoolean(INITIALIZED, false)

            notifyTrackedCharacters(prefs, bannerJson)
            notifyNextPhaseForFavoriteGames(prefs, appPrefs, bannerJson, initialized)
            if (initialized) notifyNewCodes(prefs, appPrefs, codeJson)

            prefs.edit().putBoolean(INITIALIZED, true).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notifyTrackedCharacters(prefs: android.content.SharedPreferences, json: String) {
        val games = JSONObject(json).optJSONArray("games") ?: JSONArray()
        for (i in 0 until games.length()) {
            val game = games.getJSONObject(i)
            val gameId = game.optString("id")
            if (gameId.isBlank()) continue
            val current = game.optJSONObject("current") ?: JSONObject()
            val next = game.optJSONObject("next") ?: JSONObject()
            val currentChars = readChars(current.optJSONArray("five_star")) + readChars(current.optJSONArray("four_star"))
            val nextChars = readChars(next.optJSONArray("five_star")) + readChars(next.optJSONArray("four_star"))
            for (file in trackedFiles(gameId)) {
                val name = displayName(file)
                notifyCurrent(prefs, gameId, file, name, currentChars)
                notifyNextCharacter(prefs, gameId, file, name, next, nextChars)
                if (gameId == "zzz") notifyZzzDate(prefs, gameId, file, name, next)
                if (gameId == "wuwa") notifyWuwaEnding(prefs, gameId, file, name, current)
            }
        }
    }

    private fun notifyCurrent(prefs: android.content.SharedPreferences, gameId: String, file: String, name: String, chars: List<String>) {
        val present = chars.any { sameCharacter(it, file) }
        val key = "current_${gameId}_$file"
        val old = prefs.getBoolean(key, false)
        if (present && !old) {
            showNotification("current_${gameId}_$file", gameName(gameId), "$name доступен для призыва!")
        }
        prefs.edit().putBoolean(key, present).apply()
    }

    private fun notifyNextCharacter(prefs: android.content.SharedPreferences, gameId: String, file: String, name: String, next: JSONObject, chars: List<String>) {
        val present = chars.any { sameCharacter(it, file) }
        val phase = nextSignature(next)
        val stateKey = "next_state_${gameId}_${file}"
        val phaseKey = "next_phase_${gameId}_${file}"
        if (!present) {
            prefs.edit().remove(stateKey).remove(phaseKey).apply()
            return
        }
        val unconfirmed = next.optBoolean("unconfirmed", false)
        val desired = if (unconfirmed) "likely" else "confirmed"
        val oldState = prefs.getString(stateKey, null)
        val oldPhase = prefs.getString(phaseKey, null)
        if (oldState != desired || oldPhase != phase) {
            val body = if (unconfirmed) "$name вероятно появится в следующей фазе!" else "$name подтверждён в следующей фазе!"
            showNotification("next_character_${gameId}_${file}_${desired}_${phase}", gameName(gameId), body)
            prefs.edit().putString(stateKey, desired).putString(phaseKey, phase).apply()
        }
    }

    private fun notifyNextPhaseForFavoriteGames(prefs: android.content.SharedPreferences, appPrefs: android.content.SharedPreferences, json: String, initialized: Boolean) {
        val games = JSONObject(json).optJSONArray("games") ?: JSONArray()
        for (i in 0 until games.length()) {
            val game = games.getJSONObject(i)
            val gameId = game.optString("id")
            if (!appPrefs.getBoolean("favorite_$gameId", false)) continue
            val next = game.optJSONObject("next") ?: JSONObject()
            val signature = nextSignature(next)
            val key = "game_next_$gameId"
            val old = prefs.getString(key, null)
            if (initialized && old != null && old != signature) {
                showNotification("phase_$gameId", gameName(gameId), "Следующая фаза баннера обновлена!")
            }
            prefs.edit().putString(key, signature).apply()
        }
    }

    private fun notifyZzzDate(prefs: android.content.SharedPreferences, gameId: String, file: String, name: String, next: JSONObject) {
        if (!readChars(next.optJSONArray("five_star")).any { sameCharacter(it, file) }) return
        val start = next.optString("start").takeIf { it.isNotBlank() && it != "null" } ?: return
        val key = "zzz_date_${gameId}_$file"
        if (prefs.getString(key, null) != start) {
            showNotification("zzz_date_${file}_${start}", "Zenless Zone Zero", "$name будет доступен ${formatDate(start)}!")
            prefs.edit().putString(key, start).apply()
        }
    }

    private fun notifyWuwaEnding(prefs: android.content.SharedPreferences, gameId: String, file: String, name: String, current: JSONObject) {
        if (!readChars(current.optJSONArray("five_star")).any { sameCharacter(it, file) }) return
        val end = current.optString("end").takeIf { it.isNotBlank() && it != "null" } ?: return
        try {
            val seconds = Duration.between(Instant.now(), OffsetDateTime.parse(end).toInstant()).seconds
            if (seconds in 1..86400) {
                val key = "wuwa_end_${gameId}_$file"
                if (prefs.getString(key, null) != end) {
                    showNotification("wuwa_end_${file}_${end}", "Wuthering Waves", "$name станет недоступен для призыва уже завтра!")
                    prefs.edit().putString(key, end).apply()
                }
            }
        } catch (_: Exception) { }
    }

    private fun notifyNewCodes(prefs: android.content.SharedPreferences, appPrefs: android.content.SharedPreferences, json: String) {
        val active = JSONObject(json).optJSONArray("active") ?: JSONArray()
        val byGame = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until active.length()) {
            val item = active.getJSONObject(i)
            val game = item.optString("game")
            if (appPrefs.getBoolean("favorite_$game", false)) byGame.getOrPut(game) { mutableListOf() }.add(item.optString("code"))
        }
        for ((game, codes) in byGame) {
            val key = "codes_$game"
            val old = prefs.getString(key, null)?.split('|')?.filter { it.isNotBlank() }?.toSet().orEmpty()
            val fresh = codes.distinct().filter { it !in old }
            if (fresh.isNotEmpty()) showNotification("codes_${game}_${fresh.joinToString(",")}", gameName(game), "Новые промокоды: ${fresh.joinToString(", ")}")
            prefs.edit().putString(key, codes.distinct().sorted().joinToString("|")).apply()
        }
    }

    private fun trackedFiles(gameId: String): List<String> {
        val folder = when (gameId) {
            "genshin" -> "genshin"
            "wuwa" -> "wuthering_waves"
            "zzz" -> "zenless_zone_zero"
            else -> return emptyList()
        }
        return try {
            val p = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
            val result = applicationContext.assets.list(folder).orEmpty()
                .filter { it.endsWith(".webp", true) }
                .filter { p.getBoolean("tracked_${gameId}_$it", false) }
                .toMutableList()
            p.all.keys.filter { it.startsWith("tracked_${gameId}___online_") && p.getBoolean(it, false) }
                .map { it.removePrefix("tracked_${gameId}_") }.forEach { result += it }
            if (gameId == "wuwa") {
                listOf("__wuwa-lucy.webp", "__wuwa-aemeath.webp", "__wuwa-hiyuki.webp").forEach {
                    if (p.getBoolean("tracked_wuwa_$it", false)) result += it
                }
            }
            result.distinct()
        } catch (_: Exception) { emptyList() }
    }

    private fun sameCharacter(name: String, file: String): Boolean {
        val a = slug(name)
        val b = file.substringBeforeLast('.').lowercase()
        if (b.startsWith("__online_")) return a == b.substringAfterLast('_')
        if (b == "__wuwa-lucy" && a == "lucy") return true
        if (b == "__wuwa-aemeath" && a == "aemeath") return true
        if (b == "__wuwa-hiyuki" && a == "hiyuki") return true
        if (a == b) return true
        return mapOf(
            "anby-soldier-0" to "anby-demara-soldier-0",
            "billy-kid" to "billy",
            "corin-wickes" to "corin",
            "soldier-0-anby" to "anby-demara-soldier-0",
            "augusta" to "aug",
            "orphie-and-magus" to "orhpie-and-magus"
        )[a] == b
    }

    private fun slug(value: String): String = value.lowercase()
        .replace("’", "").replace("'", "").replace(":", "").replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun displayName(file: String): String {
        val base = file.substringBeforeLast('.')
        val overrides = mapOf(
            "__wuwa-lucy" to "Lucy", "__wuwa-aemeath" to "Aemeath", "__wuwa-hiyuki" to "Hiyuki",
            "aug" to "Augusta", "anby-demara-soldier-0" to "Anby: Soldier 0",
            "orhpie-and-magus" to "Orphie & Magus", "luuk-herssen" to "Luuk Herssen"
        )
        if (base.startsWith("__online_")) return base.substringAfterLast('_').split('-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        return overrides[base] ?: base.split('-').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    private fun readChars(array: JSONArray?): List<String> = if (array == null) emptyList() else (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }

    private fun nextSignature(next: JSONObject): String = listOf(
        next.optString("version"), next.optString("start"), next.optString("end"),
        readChars(next.optJSONArray("five_star")).joinToString(","),
        readChars(next.optJSONArray("four_star")).joinToString(","),
        next.optBoolean("unconfirmed", false)
    ).joinToString("|")

    private fun gameName(id: String): String = when (id) {
        "genshin" -> "Genshin Impact"
        "wuwa" -> "Wuthering Waves"
        "zzz" -> "Zenless Zone Zero"
        else -> id
    }

    private fun formatDate(value: String): String = try {
        OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
    } catch (_: Exception) { value }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 20000; c.requestMethod = "GET"
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }

    private fun createChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Уведомления G-Codus", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun showNotification(key: String, title: String, body: String) {
        if (BuildVersion.requiresNotificationPermission && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val intent = Intent(applicationContext, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP }
        val pending = PendingIntent.getActivity(applicationContext, key.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(key.hashCode() and 0x7fffffff, notification)
    }

    private object BuildVersion {
        val requiresNotificationPermission: Boolean get() = android.os.Build.VERSION.SDK_INT >= 33
    }
}
