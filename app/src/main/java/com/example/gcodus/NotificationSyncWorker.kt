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
import java.time.Duration
import java.time.Instant

class NotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val CHANNEL_ID = "g_codus_updates"
        private const val PREFS = "g_codus_notifications"
        private const val APP_PREFS = "g_codus"
        private const val KEY_INITIALIZED = "initialized"
    }

    override fun doWork(): Result {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return Result.success()
        createChannel()
        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val bannerJson = BannerSource.fetchNormalized(applicationContext)
            val codeJson = try { PromoCodeSource.fetchJson() } catch (_: Exception) { null }
            val initialized = prefs.getBoolean(KEY_INITIALIZED, false)
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
        return listOf(next.optString("version"), next.optString("start"), next.optString("end"), chars5, chars4, next.optBoolean("unconfirmed", false).toString()).joinToString("|")
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
        when {
            newUnconfirmed && oldSignature != nextSignature(next) -> showNotification(
                ("next_unconfirmed_" + gameId).hashCode() and 0x7fffffff,
                gameName(gameId),
                "Следующая фаза: Неподтвержденная информация обновлена"
            )
            !newUnconfirmed && oldUnconfirmed -> showNotification(
                ("next_confirmed_" + gameId).hashCode() and 0x7fffffff,
                gameName(gameId),
                "Следующая фаза подтверждена!"
            )
        }

        val nextChars = readChars(next.optJSONArray("five_star")) + readChars(next.optJSONArray("four_star"))
        for (identity in trackedIdentities(gameId)) {
            val name = notificationCharacterDisplayName(identity)
            val present = nextChars.any { sameCharacter(it, identity) }
            val stateKey = "next_character_state_" + gameId + "_" + identity
            val state = prefs.getString(stateKey, null)
            if (present) {
                val desired = if (newUnconfirmed) "likely" else "confirmed"
                if (state != desired) {
                    showNotification(
                        ("next_character_" + gameId + "_" + identity + "_" + desired).hashCode() and 0x7fffffff,
                        gameName(gameId),
                        if (newUnconfirmed) "$name вероятно будет в следующей фазе!" else "$name будет доступен для призыва в следующей фазе!"
                    )
                    prefs.edit().putString(stateKey, desired).apply()
                }
            } else if (state != null) prefs.edit().remove(stateKey).apply()
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

            for (identity in trackedIdentities(gameId)) {
                val characterName = notificationCharacterDisplayName(identity)
                val bannerName = (currentChars + nextChars).firstOrNull { sameCharacter(it, identity) } ?: characterName
                notifyCurrentBannerAppearance(prefs, gameId, identity, bannerName, currentChars)
                notifyNextCharacterAppearance(prefs, gameId, identity, bannerName, next, nextChars)
                when (gameId) {
                    "zzz" -> notifyZzzDate(prefs, gameId, identity, bannerName, next)
                    "wuwa" -> notifyWuwaEnding(prefs, gameId, identity, bannerName, current)
                }
            }
        }
    }

    private fun notifyNextCharacterAppearance(prefs: android.content.SharedPreferences, gameId: String, identity: String, name: String, next: JSONObject, nextChars: List<String>) {
        if (nextChars.none { sameCharacter(it, identity) }) return
        val version = next.optString("version")
        val start = next.optString("start")
        val end = next.optString("end")
        if (version.isBlank() || version == "null") return
        val signature = listOf(version, start, end, next.optBoolean("unconfirmed", false), nextChars.joinToString(",")).joinToString("|")
        val key = "wishlist_next_" + gameId + "_" + identity
        if (prefs.getString(key, null) == signature) return
        showNotification(
            (key + signature).hashCode() and 0x7fffffff,
            gameName(gameId),
            if (next.optBoolean("unconfirmed", false)) "$name вероятно появится в следующей фазе!" else "$name подтверждён в следующей фазе!"
        )
        prefs.edit().putString(key, signature).apply()
    }

    private fun notifyCurrentBannerAppearance(prefs: android.content.SharedPreferences, gameId: String, identity: String, name: String, currentChars: List<String>) {
        val present = currentChars.any { sameCharacter(it, identity) }
        val key = "appearance_" + gameId + "_" + identity
        val old = prefs.getBoolean(key, false)
        if (present && !old) showNotification(
            ("current_" + gameId + "_" + identity).hashCode() and 0x7fffffff,
            gameName(gameId),
            "$name доступен для призыва!"
        )
        prefs.edit().putBoolean(key, present).apply()
    }

    private fun notifyZzzDate(prefs: android.content.SharedPreferences, gameId: String, identity: String, name: String, next: JSONObject) {
        val chars = readChars(next.optJSONArray("five_star"))
        if (!chars.any { sameCharacter(it, identity) }) return
        val start = next.optString("start").takeIf { it.isNotBlank() && it != "null" } ?: return
        val key = "date_" + gameId + "_" + identity
        if (prefs.getString(key, null) != start) {
            showNotification(("zzz_date_" + identity).hashCode() and 0x7fffffff, "Zenless Zone Zero", "$name будет доступен для призыва уже ${formatDate(start)}!")
            prefs.edit().putString(key, start).apply()
        }
    }

    private fun notifyWuwaEnding(prefs: android.content.SharedPreferences, gameId: String, identity: String, name: String, current: JSONObject) {
        val chars = readChars(current.optJSONArray("five_star"))
        if (!chars.any { sameCharacter(it, identity) }) return
        val end = current.optString("end").takeIf { it.isNotBlank() && it != "null" } ?: return
        try {
            val target = OffsetDateTime.parse(end).toInstant()
            val seconds = Duration.between(Instant.now(), target).seconds
            if (seconds in 1..86400) {
                val key = "ending_" + gameId + "_" + identity
                if (prefs.getString(key, null) != end) {
                    showNotification(("wuwa_end_" + identity).hashCode() and 0x7fffffff, "Wuthering Waves", "$name станет недоступен для призыва уже завтра!")
                    prefs.edit().putString(key, end).apply()
                }
            }
        } catch (_: Exception) { }
    }

    /** Reads the same tracked_v2_* identities that MainActivity writes. */
    private fun trackedIdentities(gameId: String): List<String> {
        val appPrefs = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val prefix = "tracked_v2_" + gameId + "_"
        return appPrefs.all.entries
            .filter { it.key.startsWith(prefix) && it.value == true }
            .map { it.key.removePrefix(prefix) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun sameCharacter(name: String, identity: String): Boolean {
        val a = normalizeIdentity(name)
        val b = normalizeIdentity(identity)
        if (a == b) return true
        val aliases = mapOf(
            "anbysoldier0" to "anbydemarasoldier0",
            "billykid" to "billy",
            "corinwickes" to "corin",
            "soldier0anby" to "anbydemarasoldier0",
            "augusta" to "aug",
            "orphieandmagus" to "orhpieandmagus",
            "orhpiemagus" to "orhpieandmagus",
            "mortenaxblade" to "blademortenax",
            "blademortenax" to "mortenaxblade",
            "danhengimbibitorlunae" to "imbibitorlunae",
            "imbibitorlunae" to "danhengimbibitorlunae",
            "topazandnumby" to "topaz"
        )
        return aliases[a] == b || aliases[b] == a
    }

    private fun normalizeIdentity(value: String): String = value.lowercase()
        .replace("’", "").replace("'", "")
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "")

    private fun notificationCharacterDisplayName(identity: String): String {
        return when (normalizeIdentity(identity)) {
            "anby", "anbydemara" -> "Anby"
            "billy", "billykid" -> "Billy Kid"
            "grace", "gracehoward" -> "Grace"
            "lucy", "lucyalt", "lucialt" -> "Lucy"
            "yuzuha", "ukinamiyuzuha" -> "Yuzuha"
            "nicole", "nicoledemara" -> "Nicole Demara"
            "aug" -> "Augusta"
            "orhpieandmagus", "orhpiemagus" -> "Orphie & Magus"
            "mortenaxblade", "blademortenax" -> "Mortenax Blade"
            "danhengimbibitorlunae", "imbibitorlunae" -> "Dan Heng Imbibitor Lunae"
            "topaz", "topazandnumby" -> "Topaz & Numby"
            else -> identity.split("-").joinToString(" ") { word ->
                word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
            }
        }
    }

    private fun readChars(array: JSONArray?): List<String> = if (array == null) emptyList() else buildList {
        for (i in 0 until array.length()) {
            val value = array.optString(i).trim()
            if (value.isNotBlank() && value != "null") add(value)
        }
    }

    private fun formatDate(value: String): String = try {
        OffsetDateTime.parse(value).format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy • HH:mm"))
    } catch (_: Exception) {
        value
    }

    private fun gameName(gameId: String): String = when (gameId) {
        "genshin" -> "Genshin Impact"
        "wuwa" -> "Wuthering Waves"
        "zzz" -> "Zenless Zone Zero"
        "starrail" -> "Honkai: Star Rail"
        "endfield" -> "Arknights: Endfield"
        else -> gameId
    }

    private fun createChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Обновления G-Codus", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun showNotification(id: Int, title: String, body: String) {
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = PendingIntent.getActivity(applicationContext, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(id, notification)
    }

    private fun notifyNewCodes(prefs: android.content.SharedPreferences, json: String) {
        // Promo-code notifications are intentionally left unchanged here.
    }
}
