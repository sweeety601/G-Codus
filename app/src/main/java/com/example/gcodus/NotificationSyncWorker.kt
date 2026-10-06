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
 * Background notification engine for all G-Codus update types.
 *
 * Sources of truth:
 * - banner notifications: live banner Excel tables through BannerSource
 * - new character notifications: live character Excel tables through CharacterDatabase
 * - promo-code notifications: live public code sources through PromoCodeSource
 *
 * Only small de-duplication markers are stored locally. No banner, character,
 * or promo-code database is cached by this worker.
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
        if (!notificationsAvailable()) {
            // Do not advance state while Android permission/channel is disabled.
            return Result.success()
        }

        createChannel()

        val prefs = applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return try {
            val bannerJson = BannerSource.fetchNormalized(applicationContext)
            val codeJson = try { PromoCodeSource.fetchJson() } catch (_: Exception) { null }
            val characterFetch = try {
                CharacterDatabase.fetchDetailed(applicationContext)
            } catch (_: Exception) {
                CharacterFetchResult(emptyList(), emptySet(), emptySet())
            }

            val initialized = prefs.getBoolean(KEY_INITIALIZED, false)

            notifyFollowedGames(prefs, bannerJson, initialized)
            notifyTrackedCharacters(prefs, bannerJson)
            notifyNewCharacters(prefs, characterFetch)

            if (codeJson != null) {
                if (initialized) {
                    notifyNewCodes(prefs, codeJson)
                } else {
                    // Establish the first baseline without notifying old codes.
                    saveCodeBaseline(prefs, codeJson)
                }
            }

            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply()
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    private fun notificationsAvailable(): Boolean {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }

        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        createChannel(manager)

        return manager.areNotificationsEnabled() &&
            (android.os.Build.VERSION.SDK_INT < 26 ||
                manager.getNotificationChannel(CHANNEL_ID)?.importance != NotificationManager.IMPORTANCE_NONE)
    }

    /** Notify when the active or immediate next phase changes for a followed game. */
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

            val current = readPhases(game, "current")
                .filterNot { it.optBoolean("unconfirmed", false) }
                .sortedWith(compareBy<JSONObject> { phaseStart(it) }.thenBy { phaseIdentity(it) })

            val currentSignature = current.firstOrNull()?.let(::phaseIdentity).orEmpty()
            val currentKey = "game_current_" + gameId
            val oldCurrent = prefs.getString(currentKey, null).orEmpty()

            if (initialized && currentSignature.isNotBlank() && oldCurrent.isNotBlank() &&
                oldCurrent != currentSignature
            ) {
                val version = displayVersion(current.firstOrNull())
                showNotificationSafe(
                    stableId("game_current_" + gameId + "_" + currentSignature),
                    gameName(gameId),
                    "Началась новая фаза баннеров: " + version + "."
                )
            }

            if (currentSignature.isNotBlank()) {
                prefs.edit().putString(currentKey, currentSignature).apply()
            } else {
                prefs.edit().remove(currentKey).apply()
            }

            val next = readPhases(game, "next")
                .sortedWith(compareBy<JSONObject> { phaseStart(it) }.thenBy { phaseIdentity(it) })
            val nextPhase = next.firstOrNull()
            val nextSignature = nextPhase?.let { phaseStateIdentity(it) }.orEmpty()
            val nextKey = "game_next_" + gameId
            val oldNext = prefs.getString(nextKey, null).orEmpty()

            if (initialized && nextSignature.isNotBlank() && oldNext.isNotBlank() &&
                oldNext != nextSignature
            ) {
                val oldState = oldNext.split("|").getOrNull(3).orEmpty()
                val newState = if (nextPhase?.optBoolean("unconfirmed", false) == true) {
                    "unconfirmed"
                } else {
                    "confirmed"
                }

                val body = when {
                    oldState == "unconfirmed" && newState == "confirmed" ->
                        "Следующая фаза " + displayVersion(nextPhase) + " теперь подтверждена."
                    newState == "unconfirmed" && oldState != "unconfirmed" ->
                        "Следующая фаза " + displayVersion(nextPhase) + " пока не подтверждена."
                    else ->
                        "Обновилась следующая фаза баннеров: " + displayVersion(nextPhase) + "."
                }

                showNotificationSafe(
                    stableId("game_next_" + gameId + "_" + nextSignature),
                    gameName(gameId),
                    body
                )
            }

            if (nextSignature.isNotBlank()) {
                prefs.edit().putString(nextKey, nextSignature).apply()
            } else {
                prefs.edit().remove(nextKey).apply()
            }
        }
    }

    /** Character lifecycle notifications for every tracked identity in every game. */
    private fun notifyTrackedCharacters(
        prefs: android.content.SharedPreferences,
        json: String
    ) {
        val games = JSONObject(json).optJSONArray("games") ?: JSONArray()

        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val gameId = game.optString("id")
            if (gameId.isBlank()) continue

            val current = readPhases(game, "current")
            val next = readPhases(game, "next")
                .sortedWith(compareBy<JSONObject> { phaseStart(it) }.thenBy { phaseIdentity(it) })

            for (identity in trackedIdentities(gameId)) {
                val name = notificationCharacterDisplayName(gameId, identity)

                notifyCurrentBannerAppearance(
                    prefs, gameId, identity, name, current
                )
                notifyNextCharacter(
                    prefs, gameId, identity, name, next
                )
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
        current: List<JSONObject>
    ) {
        val matching = current
            .filter { phaseContainsCharacter(gameId, it, identity) }
            .map(::phaseIdentity)
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()

        val signature = matching.joinToString("||")
        val key = "appearance_" + gameId + "_" + identity
        val old = prefs.getString(key, null).orEmpty()

        if (signature.isNotBlank() && signature != old) {
            val phase = current.firstOrNull { phaseContainsCharacter(gameId, it, identity) }
            val version = displayVersion(phase)
            val end = phase?.optString("end")
                ?.takeIf { it.isNotBlank() && it != "null" }
            val suffix = if (end != null) " До окончания: " + formatDate(end) + "." else ""

            if (showNotificationSafe(
                    stableId("current_" + gameId + "_" + identity + "_" + signature),
                    gameName(gameId),
                    name + " появился в текущем баннере " + version + "." + suffix
                )
            ) {
                prefs.edit().putString(key, signature).apply()
            }
        } else if (signature.isBlank() && old.isNotBlank()) {
            prefs.edit().remove(key).apply()
        } else if (signature.isNotBlank() && old.isBlank()) {
            // First observation is useful: a user may have tracked the character
            // after the character had already entered the active phase.
            prefs.edit().putString(key, signature).apply()
        }
    }

    private fun notifyNextCharacter(
        prefs: android.content.SharedPreferences,
        gameId: String,
        identity: String,
        name: String,
        next: List<JSONObject>
    ) {
        val matching = next
            .filter { phaseContainsCharacter(gameId, it, identity) }
            .map { it to phaseStateIdentity(it) }
            .filter { it.second.isNotBlank() }

        if (matching.isEmpty()) return

        val stateKey = "next_character_" + gameId + "_" + identity
        val seen = readStringSet(prefs.getString(stateKey, null))
        val unseen = matching.filter { (_, signature) -> signature !in seen }

        if (unseen.isNotEmpty()) {
            // Only the earliest unseen phase gets a notification on this run;
            // all matching signatures are remembered, preventing future spam.
            val first = unseen.minByOrNull { phaseStart(it.first) }!!.first
            val firstSignature = phaseStateIdentity(first)
            val version = displayVersion(first)
            val start = first.optString("start").takeIf { it.isNotBlank() && it != "null" }
            val unconfirmed = first.optBoolean("unconfirmed", false)

            val body = when {
                unconfirmed && start != null ->
                    name + " вероятно появится в " + version + " (" + formatDate(start) + ")."
                unconfirmed ->
                    name + " вероятно появится в " + version + "."
                start != null ->
                    name + " подтверждён в " + version + " (" + formatDate(start) + ")."
                else ->
                    name + " подтверждён в " + version + "."
            }

            if (showNotificationSafe(
                    stableId("next_character_" + gameId + "_" + identity + "_" + firstSignature),
                    gameName(gameId),
                    body
                )
            ) {
                prefs.edit()
                    .putString(
                        stateKey,
                        JSONArray((seen + matching.map { it.second }).toSet().sorted()).toString()
                    )
                    .apply()
            }
        } else {
            prefs.edit()
                .putString(
                    stateKey,
                    JSONArray((seen + matching.map { it.second }).toSet().sorted()).toString()
                )
                .apply()
        }

        // A date correction for the tracked character is a separate event.
        val earliest = matching.minByOrNull { phaseStart(it.first) }?.first ?: return
        val start = earliest.optString("start").takeIf { it.isNotBlank() && it != "null" } ?: return
        val dateKey = "next_date_" + gameId + "_" + identity
        val oldDate = prefs.getString(dateKey, null)

        if (oldDate != null && oldDate != start) {
            showNotificationSafe(
                stableId("next_date_" + gameId + "_" + identity + "_" + start),
                gameName(gameId),
                name + ": дата баннера обновлена — " + formatDate(start) + "."
            )
        }

        prefs.edit().putString(dateKey, start).apply()
    }

    private fun notifyEndingSoon(
        prefs: android.content.SharedPreferences,
        gameId: String,
        identity: String,
        name: String,
        current: List<JSONObject>
    ) {
        val matching = current.firstOrNull {
            phaseContainsCharacter(gameId, it, identity)
        } ?: return

        val end = matching.optString("end")
            .takeIf { it.isNotBlank() && it != "null" } ?: return
        val target = parseInstant(end) ?: return
        val seconds = Duration.between(Instant.now(), target).seconds

        if (seconds !in 1..ENDING_WINDOW_SECONDS) return

        val key = "ending_" + gameId + "_" + identity
        if (prefs.getString(key, null) == end) return

        if (showNotificationSafe(
                stableId("ending_" + gameId + "_" + identity + "_" + end),
                gameName(gameId),
                name + " станет недоступен для призыва " + formatDate(end) + "."
            )
        ) {
            prefs.edit().putString(key, end).apply()
        }
    }

    private fun notifyNewCharacters(
        prefs: android.content.SharedPreferences,
        fetch: CharacterFetchResult
    ) {
        if (fetch.characters.isEmpty()) return

        for (gameId in fetch.successfulGameIds) {
            val current = fetch.characters
                .filter { it.gameId == gameId && it.id.isNotBlank() }
                .associateBy({ it.id }, { it.name })
            if (current.isEmpty()) continue

            val key = "character_snapshot_" + gameId
            val old = readStringSet(prefs.getString(key, null))

            if (old.isNotEmpty()) {
                val newIds = current.keys.filter { it !in old }.sorted()
                if (newIds.isNotEmpty()) {
                    val names = newIds.map {
                        current[it].orEmpty().ifBlank { prettify(it) }
                    }
                    showNotificationSafe(
                        stableId("new_characters_" + gameId + "_" + newIds.joinToString(",")),
                        gameName(gameId),
                        "Новые персонажи: " + names.joinToString(", ") + "."
                    )
                }
            }

            prefs.edit()
                .putString(key, JSONArray(current.keys.sorted()).toString())
                .apply()
        }
    }

    private fun notifyNewCodes(
        prefs: android.content.SharedPreferences,
        json: String
    ) {
        val current = parseCodeKeys(json)
        val old = readStringSet(prefs.getString("codes_snapshot", null))
        if (old.isEmpty()) {
            saveCodeBaseline(prefs, json)
            return
        }

        val newCodes = current - old
        if (newCodes.isEmpty()) return

        val byGame = newCodes.groupBy { it.substringBefore("|") }
        var allSucceeded = true

        for ((gameId, entries) in byGame) {
            val codes = entries
                .map { it.substringAfter("|") }
                .sorted()

            if (!showNotificationSafe(
                    stableId("new_codes_" + gameId + "_" + codes.joinToString(",")),
                    gameName(gameId),
                    "Новые промокоды: " + codes.joinToString(", ") + "."
                )
            ) {
                allSucceeded = false
            }
        }

        if (allSucceeded) {
            prefs.edit()
                .putString("codes_snapshot", JSONArray(current.sorted()).toString())
                .apply()
        }
    }

    private fun saveCodeBaseline(
        prefs: android.content.SharedPreferences,
        json: String
    ) {
        prefs.edit()
            .putString(
                "codes_snapshot",
                JSONArray(parseCodeKeys(json).sorted()).toString()
            )
            .apply()
    }

    private fun parseCodeKeys(json: String): Set<String> {
        val root = JSONObject(json)
        val arr = root.optJSONArray("active") ?: JSONArray()

        return buildSet {
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val game = item.optString("game").trim().lowercase()
                val code = item.optString("code").trim().uppercase()
                if (game.isNotBlank() && code.isNotBlank()) {
                    add(game + "|" + code)
                }
            }
        }
    }

    /** Reads exactly the tracked_v2_* identities written by MainActivity. */
    private fun trackedIdentities(gameId: String): List<String> {
        val appPrefs = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val prefix = "tracked_v2_" + gameId + "_"

        return appPrefs.all.entries
            .filter { it.key.startsWith(prefix) && it.value == true }
            .map { canonicalCharacterId(gameId, it.key.removePrefix(prefix)) }
            .filter { it.isNotBlank() }
            .filterNot {
                val n = normalizeIdentity(it)
                n.contains("storyteller") || n.contains("sunbringer")
            }
            .distinct()
            .sorted()
    }

    private fun phaseContainsCharacter(
        gameId: String,
        phase: JSONObject,
        identity: String
    ): Boolean {
        val chars =
            readChars(phase.optJSONArray("five_star")) +
                readChars(phase.optJSONArray("characters")) +
                readChars(phase.optJSONArray("four_star"))

        return chars.any { sameCharacter(gameId, it, identity) }
    }

    private fun readPhases(game: JSONObject, key: String): List<JSONObject> {
        return when (val raw = game.opt(key)) {
            is JSONObject -> listOf(raw)
            is JSONArray -> (0 until raw.length())
                .mapNotNull { raw.optJSONObject(it) }
            else -> emptyList()
        }
    }

    private fun phaseIdentity(phase: JSONObject): String =
        listOf(
            phase.optString("version", phase.optString("phase")),
            phase.optString("start"),
            phase.optString("end")
        ).joinToString("|")

    private fun phaseStateIdentity(phase: JSONObject): String =
        listOf(
            phase.optString("version", phase.optString("phase")),
            phase.optString("start"),
            phase.optString("end"),
            if (phase.optBoolean("unconfirmed", false)) "unconfirmed" else "confirmed"
        ).joinToString("|")

    private fun phaseStart(phase: JSONObject): Instant =
        parseInstant(
            phase.optString("start").takeIf { it.isNotBlank() && it != "null" } ?: ""
        ) ?: Instant.MAX

    private fun displayVersion(phase: JSONObject?): String =
        phase?.optString("version", phase.optString("phase"))
            .orEmpty()
            .ifBlank { "следующая фаза" }

    /** Game-aware aliases. Banner and tracking feeds can use different slug forms. */
    private fun sameCharacter(gameId: String, name: String, identity: String): Boolean =
        canonicalCharacterId(gameId, name) == canonicalCharacterId(gameId, identity)

    private fun canonicalCharacterId(gameId: String, value: String): String {
        val n = normalizeIdentity(value)

        return when (gameId) {
            "genshin" -> when (n) {
                "kaedeharakazuha", "kazuha" -> "kazuha"
                "raidenshogun", "raiden" -> "raidenshogun"
                "tartaglia", "childe" -> "tartaglia"
                "wanderer", "scaramouche" -> "wanderer"
                "kamisatoayaka", "ayaka" -> "kamisatoayaka"
                "kamisatoayato", "ayato" -> "kamisatoayato"
                else -> n
            }
            "wuwa" -> when (n) {
                "theshorekeeper", "shorekeeper" -> "shorekeeper"
                "yangyangxuanling", "yangyang" -> "yangyang"
                else -> n
            }
            "zzz" -> when (n) {
                "billy", "billykid" -> "billykid"
                "anby", "anbydemara", "anbysoldier0", "soldier0anby" -> "anby"
                "grace", "gracehoward" -> "grace"
                "lucy", "lucyalt", "lucialt" -> "lucy"
                "yuzuha", "ukinamiyuzuha" -> "yuzuha"
                "nicole", "nicoledemara" -> "nicole"
                "orphieandmagus", "orhpieandmagus", "orhpiemagus" -> "orphieandmagus"
                else -> n
            }
            "starrail" -> when (n) {
                "blademortenax", "mortenaxblade" -> "mortenaxblade"
                "imbibitorlunae", "danhengimbibitorlunae" -> "imbibitorlunae"
                "topaz", "topazandnumby" -> "topaz"
                else -> n
            }
            "endfield" -> when (n) {
                "orhpieandmagus", "orhpiemagus", "orphieandmagus" -> "orphieandmagus"
                else -> n
            }
            else -> n
        }
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

    private fun prettify(value: String): String =
        value.split("-").joinToString(" ") { word ->
            word.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase() else ch.toString()
            }
        }

    private fun readChars(array: JSONArray?): List<String> =
        if (array == null) {
            emptyList()
        } else {
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotBlank() && value != "null") add(value)
                }
            }
        }

    private fun readStringSet(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()

        return try {
            val arr = JSONArray(raw)
            buildSet {
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i)
                    if (value.isNotBlank()) add(value)
                }
            }
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun parseInstant(value: String): Instant? = try {
        OffsetDateTime.parse(value).toInstant()
    } catch (_: Exception) {
        try {
            Instant.parse(value)
        } catch (_: Exception) {
            null
        }
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

    private fun stableId(value: String): Int =
        value.hashCode() and 0x7fffffff

    private fun createChannel(manager: NotificationManager? = null) {
        val target = manager ?: (
            applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            )

        target.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Обновления G-Codus",
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun showNotificationSafe(id: Int, title: String, body: String): Boolean =
        try {
            showNotification(id, title, body)
            true
        } catch (_: Exception) {
            false
        }

    private fun showNotification(id: Int, title: String, body: String) {
        val intent = applicationContext.packageManager
            .getLaunchIntentForPackage(applicationContext.packageName)
            ?: return

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

        val manager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager
        manager.notify(id, notification)
    }
}
