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
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.Duration

class NotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val CHANNEL_ID = "g_codus_updates"
        private const val PREFS = "g_codus_notifications"
        private const val APP_PREFS = "g_codus"
        private const val CODES_URL = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/app/src/main/assets/codes_feed.json"
        private const val KEY_INITIALIZED = "initialized"
        private const val KEY_CODES_PREFIX = "codes_"
    }

    override fun doWork(): Result {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        createChannel()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val bannerJson = BannerSource.fetchNormalized(applicationContext)
            val codeJson = try { fetch(CODES_URL) } catch (_: Exception) { null }
            applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
                .edit().putString("banner_feed", bannerJson).apply()
            val initialized = prefs.getBoolean(KEY_INITIALIZED, false)
            // Character tracking is user-selected, so it may notify even on the
            // first sync if the tracked character is already in the relevant state.
            notifyFollowedGames(prefs, bannerJson, initialized)
            notifyTrackedCharacters(prefs, bannerJson)
            if (initialized && codeJson != null) notifyNewCodes(prefs, codeJson)
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            Result.success()
        } catch (_: Exception) { Result.retry() }
    }

    private fun notifyFollowedGames(prefs: android.content.SharedPreferences, json: String, initialized: Boolean) {
        val games = JSONObject(json).optJSONArray("games") ?: JSONArray()
        val appPrefs = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

        for (i in 0 until games.length()) {
            val game = games.getJSONObject(i)
            val gameId = game.optString("id")
            if (gameId.isBlank() || !appPrefs.getBoolean("favorite_" + gameId, false)) continue

            val current = game.optJSONObject("current") ?: JSONObject()
            val next = game.optJSONObject("next") ?: JSONObject()
            val signature = nextSignature(next)
            val key = "next_" + gameId
            val old = prefs.getString(key, null)

            if (initialized && old != null && old != signature) {
                notifyNextPhaseChange(prefs, gameId, old, next, appPrefs)
            }

            prefs.edit().putString(key, signature).apply()
        }
    }

    private fun nextSignature(next: JSONObject): String {
        val chars5 = readChars(next.optJSONArray("five_star")).joinToString(",")
        val chars4 = readChars(next.optJSONArray("four_star")).joinToString(",")
        return listOf(
            next.optString("version"),
            next.optString("start"),
            next.optString("end"),
            chars5,
            chars4,
            next.optBoolean("unconfirmed", false).toString()
        ).joinToString("|")
    }

    private fun notifyNextPhaseChange(
        prefs: android.content.SharedPreferences,
        gameId: String,
        oldSignature: String,
        next: JSONObject,
        appPrefs: android.content.SharedPreferences
    ) {
        val oldParts = oldSignature.split("|")
        val oldUnconfirmed = oldParts.getOrNull(5)?.toBooleanStrictOrNull() ?: false
        val newUnconfirmed = next.optBoolean("unconfirmed", false)
        val nextChars = readChars(next.optJSONArray("five_star")) + readChars(next.optJSONArray("four_star"))
        when {
            newUnconfirmed && oldSignature != nextSignature(next) -> {
                showNotification(
                    ("next_unconfirmed_" + gameId).hashCode() and 0x7fffffff,
                    gameName(gameId),
                    "Следующая фаза: Неподтвержденная информация обновлена"
                )
            }
            !newUnconfirmed && oldUnconfirmed -> {
                showNotification(
                    ("next_confirmed_" + gameId).hashCode() and 0x7fffffff,
                    gameName(gameId),
                    "Следующая фаза подтверждена!"
                )
            }
        }

        // Wishlist/tracking alerts are emitted only for games the user marked
        // as favourite. A character in a leaked next phase gets the softer
        // wording; once that phase becomes official, the wording changes.
        for (file in trackedFiles(gameId)) {
            val name = displayName(file)
            val present = nextChars.any { sameCharacter(it, file) }
            val stateKey = "next_character_state_" + gameId + "_" + file
            val state = prefs.getString(stateKey, null)

            if (present) {
                val desired = if (newUnconfirmed) "likely" else "confirmed"
                if (state != desired) {
                    val body = if (newUnconfirmed) {
                        "$name вероятно будет в следующей фазе!"
                    } else {
                        "$name будет доступен для призыва в следующей фазе!"
                    }
                    showNotification(
                        ("next_character_" + gameId + "_" + file + "_" + desired).hashCode() and 0x7fffffff,
                        gameName(gameId),
                        body
                    )
                    prefs.edit().putString(stateKey, desired).apply()
                }
            } else if (state != null) {
                prefs.edit().remove(stateKey).apply()
            }
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
            val currentChars = readChars(current.optJSONArray("five_star")) +
                readChars(current.optJSONArray("four_star"))
            val nextChars = readChars(next.optJSONArray("five_star")) +
                readChars(next.optJSONArray("four_star"))

            for (file in trackedFiles(gameId)) {
                val characterName = displayName(file)
                val bannerName = bannerCharacterName(currentChars, nextChars, file) ?: characterName

                notifyCurrentBannerAppearance(prefs, gameId, file, bannerName, currentChars)
                notifyNextCharacterAppearance(prefs, gameId, file, bannerName, next, nextChars)

                when (gameId) {
                    "zzz" -> notifyZzzDate(prefs, gameId, file, bannerName, next)
                    "wuwa" -> notifyWuwaEnding(prefs, gameId, file, bannerName, current)
                }
            }
        }
    }

    private fun notifyNextCharacterAppearance(
        prefs: android.content.SharedPreferences,
        gameId: String,
        file: String,
        name: String,
        next: JSONObject,
        nextChars: List<String>
    ) {
        if (nextChars.none { sameCharacter(it, file) }) return
        val version = next.optString("version")
        val start = next.optString("start")
        val end = next.optString("end")
        if (version.isBlank() || version == "null") return
        val signature = listOf(version, start, end, next.optBoolean("unconfirmed", false), nextChars.joinToString(",")).joinToString("|")
        val key = "wishlist_next_" + gameId + "_" + file
        if (prefs.getString(key, null) == signature) return
        val body = if (next.optBoolean("unconfirmed", false)) {
            name + " вероятно появится в следующей фазе!"
        } else {
            name + " подтверждён в следующей фазе!"
        }
        showNotification(
            (key + signature).hashCode() and 0x7fffffff,
            gameName(gameId),
            body
        )
        prefs.edit().putString(key, signature).apply()
    }

    private fun notifyCurrentBannerAppearance(
        prefs: android.content.SharedPreferences,
        gameId: String,
        file: String,
        name: String,
        currentChars: List<String>
    ) {
        val present = currentChars.any { sameCharacter(it, file) }
        val key = "appearance_" + gameId + "_" + file
        val old = prefs.getBoolean(key, false)
        if (present && !old) {
            showNotification(
                ("current_" + gameId + "_" + file).hashCode() and 0x7fffffff,
                gameName(gameId),
                "$name доступен для призыва!"
            )
        }
        prefs.edit().putBoolean(key, present).apply()
    }

    private fun notifyGenshinAppearance(prefs: android.content.SharedPreferences, gameId: String, file: String, name: String, currentChars: List<String>) {
        val present = currentChars.any { sameCharacter(it, file) }
        val key = "appearance_" + gameId + "_" + file
        val old = prefs.getBoolean(key, false)
        if (present && !old) showNotification(("genshin_" + file).hashCode() and 0x7fffffff, "Genshin Impact", "$name доступен для призыва!")
        prefs.edit().putBoolean(key, present).apply()
    }

    private fun notifyZzzDate(prefs: android.content.SharedPreferences, gameId: String, file: String, name: String, next: JSONObject) {
        val chars = readChars(next.optJSONArray("five_star"))
        if (!chars.any { sameCharacter(it, file) }) return
        val start = next.optString("start").takeIf { it.isNotBlank() && it != "null" } ?: return
        val key = "date_" + gameId + "_" + file
        val old = prefs.getString(key, null)
        if (old != start) {
            showNotification(("zzz_date_" + file).hashCode() and 0x7fffffff, "Zenless Zone Zero", "$name будет доступен для призыва уже ${formatDate(start)}!")
            prefs.edit().putString(key, start).apply()
        }
    }

    private fun notifyWuwaEnding(prefs: android.content.SharedPreferences, gameId: String, file: String, name: String, current: JSONObject) {
        val chars = readChars(current.optJSONArray("five_star"))
        if (!chars.any { sameCharacter(it, file) }) return
        val end = current.optString("end").takeIf { it.isNotBlank() && it != "null" } ?: return
        try {
            val target = OffsetDateTime.parse(end).toInstant()
            val seconds = Duration.between(java.time.Instant.now(), target).seconds
            if (seconds in 1..86400) {
                val key = "ending_" + gameId + "_" + file
                if (prefs.getString(key, null) != end) {
                    showNotification(("wuwa_end_" + file).hashCode() and 0x7fffffff, "Wuthering Waves", "$name станет недоступен для призыва уже завтра!")
                    prefs.edit().putString(key, end).apply()
                }
            }
        } catch (_: Exception) { }
    }

    private fun trackedFiles(gameId: String): List<String> {
        val appPrefs = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val prefix = "tracked_" + gameId + "_"

        // The Wish List is the source of truth. Do not require the character to
        // exist in the local portrait directory: this is what allows reruns,
        // 4★ characters and online-only portraits to receive notifications too.
        val tracked = appPrefs.all.entries
            .filter { it.key.startsWith(prefix) && it.value == true }
            .map { it.key.removePrefix(prefix) }
            .filter { it.isNotBlank() }
            .toMutableSet()

        val folder = when (gameId) {
            "genshin" -> "genshin"
            "wuwa" -> "wuthering_waves"
            "zzz" -> "zenless_zone_zero"
            else -> null
        }

        if (folder != null) {
            applicationContext.assets.list(folder).orEmpty()
                .filter { it.endsWith(".webp", true) }
                .forEach { file ->
                    if (appPrefs.getBoolean(prefix + file, false)) tracked += file
                }
        }

        // Preserve compatibility with the special WuWa virtual portrait keys.
        if (gameId == "wuwa") {
            listOf("__wuwa-lucy.webp", "__wuwa-aemeath.webp", "__wuwa-hiyuki.webp").forEach {
                if (appPrefs.getBoolean(prefix + it, false)) tracked += it
            }
        }

        return tracked.toList().sorted()
    }

    private fun sameCharacter(name: String, file: String): Boolean {
        val a = slug(name)
        val b = file.substringBeforeLast(".").lowercase()
        if (b.startsWith("__online_")) {
            val onlineSlug = b.substringAfterLast("_")
            return a == onlineSlug
        }
        if (b == "__wuwa-lucy" && a == "lucy") return true
        if (b == "__wuwa-aemeath" && a == "aemeath") return true
        if (b == "__wuwa-hiyuki" && a == "hiyuki") return true
        if (a == b) return true
        val aliases = mapOf(
            "anby-soldier-0" to "anby-demara-soldier-0",
            "billy-kid" to "billy",
            "corin-wickes" to "corin",
            "soldier-0-anby" to "anby-demara-soldier-0",
            "anby-demara-soldier-0" to "anby-demara-soldier-0",
            "augusta" to "aug",
            "orphie-and-magus" to "orhpie-and-magus"
        )
        return aliases[a] == b
    }

    private fun bannerCharacterName(current: List<String>, next: List<String>, file: String): String? =
        (current + next).firstOrNull { sameCharacter(it, file) }

    private fun slug(value: String): String = value.lowercase()
        .replace("’", "").replace("'", "").replace(":", "").replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun displayName(file: String): String {
        val base = file.substringBeforeLast(".")
        if (base.startsWith("__online_")) {
            return base.substringAfterLast("_").split("-").joinToString(" ") { word ->
                word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
        }
        val overrides = mapOf(
            "__wuwa-lucy" to "Lucy",
            "__wuwa-aemeath" to "Aemeath",
            "__wuwa-hiyuki" to "Hiyuki",
            "lucy-alt" to "Lucy",
            "aug" to "Augusta",
            "arataki-itto" to "Arataki Itto",
            "yumemizuki-mizuki" to "Yumemizuki Mizuki",
            "yae-miko" to "Yae Miko",
            "yun-jin" to "Yun Jin",
            "anby-demara-soldier-0" to "Anby: Soldier 0",
            "orhpie-and-magus" to "Orphie & Magus",
            "luuk-herssen" to "Luuk Herssen"
        )
        return overrides[base] ?: base.split("-").joinToString(" ") { it.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() } }
    }

    private fun readChars(array: JSONArray?): List<String> = if (array == null) emptyList()
        else (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }

    private fun notifyNewCodes(prefs: android.content.SharedPreferences, json: String) {
        val active = JSONObject(json).optJSONArray("active") ?: JSONArray()
        val byGame = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until active.length()) {
            val item = active.getJSONObject(i)
            val game = item.optString("game")
            if (isFavorite(game)) byGame.getOrPut(game) { mutableListOf() }.add(item.optString("code"))
        }
        for ((game, codes) in byGame) {
            val key = KEY_CODES_PREFIX + game
            val old = prefs.getString(key, null)
            val signature = codes.distinct().sorted().joinToString("|")
            if (old != null) {
                val oldSet = old.split("|").filter { it.isNotBlank() }.toSet()
                val newCodes = codes.filter { it !in oldSet }.distinct()
                if (newCodes.isNotEmpty()) showNotification((game.hashCode() * 31 + 2) and 0x7fffffff, gameName(game) + ": Новые промокоды", newCodes.joinToString("\n") { "💫$it" })
            }
            prefs.edit().putString(key, signature).apply()
        }
    }

    private fun isFavorite(gameId: String): Boolean = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE).getBoolean("favorite_" + gameId, false)
    private fun gameName(id: String) = when (id) { "genshin" -> "Genshin Impact"; "wuwa" -> "Wuthering Waves"; "zzz" -> "Zenless Zone Zero"; else -> id }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 20000
        connection.setRequestProperty("User-Agent", "G-Codus/1.0")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun formatDate(value: String): String = try {
        OffsetDateTime.parse(value).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("dd.MM.yyyy"))
    } catch (_: Exception) { value }

    private fun createChannel() {
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            val manager = applicationContext.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "G-Codus: обновления", NotificationManager.IMPORTANCE_HIGH))
        }
    }

    private fun showNotification(id: Int, title: String, body: String) {
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = PendingIntent.getActivity(applicationContext, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(title).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true).setContentIntent(pending).build()
        androidx.core.app.NotificationManagerCompat.from(applicationContext).notify(id, notification)
    }
}