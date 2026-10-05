from pathlib import Path
import re

p = Path('app/src/main/java/com/example/gcodus/NotificationSyncWorker.kt')
s = p.read_text()

# Restore the complete promo-code notification implementation if a previous patch
# accidentally replaced it with a stub.
s = re.sub(
    r'    private fun notifyNewCodes\(prefs: android\.content\.SharedPreferences, json: String\) \{.*?\n    \}\n',
    '''    private fun notifyNewCodes(prefs: android.content.SharedPreferences, json: String) {
        val active = JSONObject(json).optJSONArray("active") ?: JSONArray()
        val byGame = mutableMapOf<String, MutableList<String>>()
        for (i in 0 until active.length()) {
            val item = active.getJSONObject(i)
            val game = item.optString("game")
            if (isFavorite(game)) byGame.getOrPut(game) { mutableListOf() }.add(item.optString("code"))
        }
        for ((game, codes) in byGame) {
            val key = "codes_" + game
            val old = prefs.getString(key, null)
            val signature = codes.distinct().sorted().joinToString("|")
            if (old != null) {
                val oldSet = old.split("|").filter { it.isNotBlank() }.toSet()
                val newCodes = codes.filter { it !in oldSet }.distinct()
                if (newCodes.isNotEmpty()) {
                    showNotification(
                        (game.hashCode() * 31 + 2) and 0x7fffffff,
                        gameName(game) + ": Новые промокоды",
                        newCodes.joinToString("\\n") { "💫$it" }
                    )
                }
            }
            prefs.edit().putString(key, signature).apply()
        }
    }
''', s, flags=re.S)

# MainActivity writes tracked_v2_<game>_<canonical identity>. The old worker
# incorrectly read tracked_<game>_<filename>, so almost all character alerts
# could silently miss. Replace the whole reader with the real source of truth.
s = re.sub(
    r'    private fun trackedFiles\(gameId: String\): List<String> \{.*?\n    \}\n\n    private fun sameCharacter',
    '''    private fun trackedFiles(gameId: String): List<String> {
        val appPrefs = applicationContext.getSharedPreferences(APP_PREFS, Context.MODE_PRIVATE)
        val prefix = "tracked_v2_" + gameId + "_"
        return appPrefs.all.entries
            .filter { it.key.startsWith(prefix) && it.value == true }
            .map { it.key.removePrefix(prefix) }
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    private fun sameCharacter''', s, flags=re.S)

# Match banner metadata to canonical identities, including known aliases.
s = re.sub(
    r'    private fun sameCharacter\(name: String, file: String\): Boolean \{.*?\n    \}\n\n    private fun bannerCharacterName',
    '''    private fun sameCharacter(name: String, file: String): Boolean {
        val a = normalizeIdentity(name)
        val b = normalizeIdentity(file)
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

    private fun notificationCharacterDisplayName(identity: String): String = when (normalizeIdentity(identity)) {
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

    private fun bannerCharacterName''', s, flags=re.S)

# Use canonical identities for all character notification state keys and matching.
s = s.replace('for (file in trackedFiles(gameId)) {\n                val name = displayName(file)', 'for (file in trackedFiles(gameId)) {\n                val name = notificationCharacterDisplayName(file)')
s = s.replace('for (file in trackedFiles(gameId)) {\n                val characterName = displayName(file)', 'for (file in trackedFiles(gameId)) {\n                val characterName = notificationCharacterDisplayName(file)')

p.write_text(s)
