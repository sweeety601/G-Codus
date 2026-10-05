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
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/**
 * Checks every character selected in the Wish List against the same normalized
 * five-game banner feed used by the UI.
 *
 * Character notifications are deliberately game-agnostic: every tracked
 * character can receive the same lifecycle events regardless of whether it is
 * Genshin, WuWa, ZZZ, HSR, or Endfield.
 */
class NotificationSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : Worker(appContext, workerParams) {

    companion object {
        private const val CHANNEL_ID = "g_codus_updates"
        private const val PREFS = "g_codus_notifications"
        private const val APP_PREFS = "g_codus"
        private const val KEY_INITIALIZED = "initialized"
        private const val ENDING_WINDOW_SECONDS = 24L * 60L * 60L
    }

    override fun doWork(): Result {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

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
        } catch (_: Exception) {
            Result.retry()
        }
    }

    /** Game-level next-phase notification for games explicitly marked favorite. */
    private fun notifyFollowedGames(
        prefs: android.content.SharedPreferences,
        json: String,
        initialized: Boolean
    ) {
        val games = JSONObject(json).optJSONArray("games") ?: JSONArray()
        val appPrefs = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)

        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val gameId = game.optString("id")
            if (gameId.isBlank() || !appPrefs.getBoolean("favorite_" + gameId, false)) continue

            val next = game.optJSONObject("next") ?: JSONObject()
            val signature = phaseSignature(next)
            val key = "next_" + gameId
            val old = prefs.getString(key, null)

            if (initialized && old != null && old != signature) {
                val oldUnconfirmed = old.split("|").getOrNull(5)?.toBooleanStrictOrNull() ?: false
                val newUnconfirmed = next.optBoolean("unconfirmed", false)
                when {
                    newUnconfirmed && !oldUnconfirmed -> showNotification(
                        stableId("phase_unconfirmed_$gameId"),
                        gameName(gameId),
                        "Следующая фаза пока не подтверждена"
                    )
                    !newUnconfirmed && oldUnconfirmed -> showNotification(
                        stableId("phase_confirmed_$gameId"),
                        gameName(gameId),
                        "Следующая фаза подтверждена"
                    )
                    else -> showNotification(
                        stableId("phase_changed_$gameId"),
                        gameName(gameId),
                        "Обновилась информация о следующей фазе"
                    )
                }
            }
            prefs.edit().putString(key, signature).apply()
        }
    }

    /**
     * All character lifecycle events are checked for every tracked identity in
     * all five supported games.
     */
    private fun notifyTrackedCharacters(
        prefs: android.content.SharedPreferences,
        json: String
    ) {
        val games = JSONObject(json).optJSONArray("games") ?: JSONArray()

        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val gameId = game.optString("id")
            if (gameId.isBlank()) continue

            val current = game.optJSONObject("current") ?: JSONObject()
            val next = game.optJSONObject("next") ?: JSONObject()

            val currentChars = readChars(current.optJSONArray("five_star")) +
                readChars(current.optJSONArray("four_star"))
            val nextChars = readChars(next.optJSONArray("five_star")) +
                readChars(next.optJSONArray("four_star"))

            for (identity in trackedIdentities(gameId)) {
                val name = notificationCharacterDisplayName(gameId, identity)

                // 1. Character became available in the current banner.
                notifyCurrentBannerAppearance(
                    prefs, gameId, identity, name, currentChars, current
                )

                // 2. Character appeared in the next phase, with confirmed /
                //    unconfirmed state and the phase start date.
                notifyNextCharacter(
                    prefs, gameId, identity, name, next, nextChars
                )

                // 3. Character's current banner is ending soon. This is now
                //    supported by all five games, not just WuWa.
                notifyEndingSoon(
                    prefs, gameId, identity, name, current
                )
            }
        }
    }

    private fun notifyCurrentBannerAppearance(
        prefs: android.content.SharedPreferences,
        gameId: String,
        identity: String,
        name: String,
        currentChars: List<String>,
        current: JSONObject
    ) {
        val present = currentChars.any { sameCharacter(gameId, it, identity) }
        val key = "appearance_${gameId}_${identity}"
        val old = prefs.getBoolean(key, false)

        if (present && !old) {
            val end = current.optString("end").takeIf { it.isNotBlank() && it != "null" }
            val suffix = if (end != null) " До окончания: ${formatDate(end)}." else ""
            showNotification(
                stableId("current_${gameId}_${identity}"),
                gameName(gameId),
                "$name доступен для призыва!$suffix"
            )
        }
        prefs.edit().putBoolean(key, present).apply()
    }

    private fun notifyNextCharacter(
        prefs: android.content.SharedPreferences,
        gameId: String,
        identity: String,
        name: String,
        next: JSONObject,
        nextChars: List<String>
    ) {
        val present = nextChars.any { sameCharacter(gameId, it, identity) }
        val stateKey = "next_character_${gameId}_${identity}"

        if (!present) {
            if (prefs.contains(stateKey)) prefs.edit().remove(stateKey).apply()
            return
        }

        val version = next.optString("version").takeIf { it.isNotBlank() && it != "null" }
        val start = next.optString("start").takeIf { it.isNotBlank() && it != "null" }
        val end = next.optString("end").takeIf { it.isNotBlank() && it != "null" }
        if (version == null) return

        val unconfirmed = next.optBoolean("unconfirmed", false)
        val state = if (unconfirmed) "unconfirmed" else "confirmed"
        val signature = listOf(version, start ?: "", end ?: "", state).joinToString("|")
        val oldSignature = prefs.getString(stateKey, null)

        if (oldSignature != signature) {
            val body = when {
                unconfirmed && start != null -> "$name вероятно появится в следующей фазе (${formatDate(start)})."
                unconfirmed -> "$name вероятно появится в следующей фазе."
                start != null -> "$name подтверждён в следующей фазе (${formatDate(start)})."
                else -> "$name подтверждён в следующей фазе."
            }
            showNotification(
                stableId("next_${gameId}_${identity}_${signature}"),
                gameName(gameId),
                body
            )
            prefs.edit().putString(stateKey, signature).apply()
        }

        // Separate date notification: fires again only when the actual start
        // date changes, so a later metadata correction is not silently missed.
        if (start != null) {
            val dateKey = "next_date_${gameId}_${identity}"
            if (prefs.getString(dateKey, null) != start) {
                showNotification(
                    stableId("next_date_${gameId}_${identity}_${start}"),
                    gameName(gameId),
                    "$name станет доступен для призыва ${formatDate(start)}."
                )
                prefs.edit().putString(dateKey, start).apply()
            }
        }
    }

    private fun notifyEndingSoon(
        prefs: android.content.SharedPreferences,
        gameId: String,
        identity: String,
        name: String,
        current: JSONObject
    ) {
        if (current.optJSONArray("five_star") == null && current.optJSONArray("four_star") == null) return

        val currentChars = readChars(current.optJSONArray("five_star")) +
            readChars(current.optJSONArray("four_star"))
        if (currentChars.none { sameCharacter(gameId, it, identity) }) return

        val end = current.optString("end").takeIf { it.isNotBlank() && it != "null" } ?: return
        val target = parseInstant(end) ?: return
        val seconds = Duration.between(Instant.now(), target).seconds
        if (seconds !in 1..ENDING_WINDOW_SECONDS) return

        val key = "ending_${gameId}_${identity}"
        if (prefs.getString(key, null) == end) return

        showNotification(
            stableId("ending_${gameId}_${identity}_${end}"),
            gameName(gameId),
            "$name станет недоступен для призыва ${formatDate(end)}."
        )
        prefs.edit().putString(key, end).apply()
    }

    /** Reads exactly the tracked_v2_* identities written by MainActivity. */
    private fun trackedIdentities(gameId: String): List<String> {
        val appPrefs = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val prefix = "tracked_v2_${gameId}_"
        return appPrefs.all.entries
            .filter { it.key.startsWith(prefix) && it.value == true }
            .map { it.key.removePrefix(prefix) }
            .filter { it.isNotBlank() }
            .filterNot {
                val n = normalizeIdentity(it)
                n.contains("storyteller") || n.contains("sunbringer")
            }
            .distinct()
            .sorted()
    }

    /**
     * Game-aware aliases. The tracking key is the canonical local identity,
     * while the online banner feed may use a different display/slug form.
     */
    private fun sameCharacter(gameId: String, name: String, identity: String): Boolean {
        val a = normalizeIdentity(name)
        val b = normalizeIdentity(identity)
        if (a == b) return true

        val aliases = when (gameId) {
            "zzz" -> mapOf(
                "anby" to setOf("anbydemara", "anbysoldier0", "soldier0anby"),
                "billy" to setOf("billykid"),
                "grace" to setOf("gracehoward"),
                "lucy" to setOf("lucyalt", "lucialt"),
                "yuzuha" to setOf("ukinamiyuzuha"),
                "nicole" to setOf("nicoledemara")
            )
            "starrail" -> mapOf(
                "mortenaxblade" to setOf("blademortenax"),
                "blademortenax" to setOf("mortenaxblade"),
                "danhengimbibitorlunae" to setOf("imbibitorlunae"),
                "imbibitorlunae" to setOf("danhengimbibitorlunae"),
                "topaz" to setOf("topazandnumby")
            )
            "endfield" -> mapOf(
                "orhpieandmagus" to setOf("orhpieandmagus", "orhpiemagus"),
                "orhpiemagus" to setOf("orhpieandmagus")
            )
            else -> emptyMap()
        }

        return aliases[a]?.contains(b) == true || aliases[b]?.contains(a) == true
    }

    private fun normalizeIdentity(value: String): String = value.lowercase()
        .replace("’", "")
        .replace("'", "")
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "")

    private fun notificationCharacterDisplayName(gameId: String, identity: String): String {
        return when (gameId) {
            "zzz" -> when (normalizeIdentity(identity)) {
                "anby", "anbydemara", "anbysoldier0", "soldier0anby" -> "Anby"
                "billy", "billykid" -> "Billy Kid"
                "grace", "gracehoward" -> "Grace"
                "lucy", "lucyalt", "lucialt" -> "Lucy"
                "yuzuha", "ukinamiyuzuha" -> "Yuzuha"
                "nicole", "nicoledemara" -> "Nicole Demara"
                else -> prettify(identity)
            }
            "starrail" -> when (normalizeIdentity(identity)) {
                "mortenaxblade", "blademortenax" -> "Mortenax Blade"
                "danhengimbibitorlunae", "imbibitorlunae" -> "Dan Heng Imbibitor Lunae"
                "topaz", "topazandnumby" -> "Topaz & Numby"
                else -> prettify(identity)
            }
            else -> prettify(identity)
        }
    }

    private fun prettify(value: String): String = value.split("-").joinToString(" ") { word ->
        word.replaceFirstChar { ch -> if (ch.isLowerCase()) ch.titlecase() else ch.toString() }
    }

    private fun readChars(array: JSONArray?): List<String> = if (array == null) {
        emptyList()
    } else {
        buildList {
            for (i in 0 until array.length()) {
                val value = array.optString(i).trim()
                if (value.isNotBlank() && value != "null") add(value)
            }
        }
    }

    private fun phaseSignature(next: JSONObject): String = listOf(
        next.optString("version"),
        next.optString("start"),
        next.optString("end"),
        readChars(next.optJSONArray("five_star")).joinToString(","),
        readChars(next.optJSONArray("four_star")).joinToString(","),
        next.optBoolean("unconfirmed", false).toString()
    ).joinToString("|")

    private fun parseInstant(value: String): Instant? = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: Exception) {
        try { Instant.parse(value) } catch (_: Exception) { null }
    }

    private fun formatDate(value: String): String = try {
        OffsetDateTime.parse(value).format(
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy • HH:mm")
        )
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

    private fun stableId(value: String): Int = value.hashCode() and 0x7fffffff

    private fun createChannel() {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Обновления G-Codus",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun showNotification(id: Int, title: String, body: String) {
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
        val pending = PendingIntent.getActivity(
            applicationContext,
            id,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
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
