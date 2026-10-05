# Repository character migration V4: Excel -> JSON metadata + images/<id>.webp.
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PKG = ROOT / "app/src/main/java/com/example/gcodus"
MAIN = PKG / "MainActivity.kt"
DB = PKG / "CharacterDatabase.kt"

NEW_DB = r'''package com.example.gcodus

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class OnlineCharacter(
    val gameId: String,
    val name: String,
    val slug: String,
    val announced: Boolean,
    val portraitUrl: String,
    val rarity: Int = 0,
    val element: String = "",
    val id: String = ""
)

object CharacterDatabase {
    private const val FEED_URL = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/data/characters.json"
    private const val CACHE_KEY = "repository_character_feed_v1"

    fun fetch(context: Context): List<OnlineCharacter> {
        val json = try { get(FEED_URL) } catch (_: Exception) { null }
        if (!json.isNullOrBlank()) {
            context.getSharedPreferences("g_codus", Context.MODE_PRIVATE).edit().putString(CACHE_KEY, json).apply()
            return parse(json)
        }
        val cached = context.getSharedPreferences("g_codus", Context.MODE_PRIVATE).getString(CACHE_KEY, null)
        return if (!cached.isNullOrBlank()) parse(cached) else emptyList()
    }

    private fun parse(json: String): List<OnlineCharacter> {
        val root = JSONObject(json)
        val games = root.optJSONArray("games") ?: return emptyList()
        val result = mutableListOf<OnlineCharacter>()
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val gameId = game.optString("id")
            val characters = game.optJSONArray("characters") ?: continue
            for (j in 0 until characters.length()) {
                val c = characters.optJSONObject(j) ?: continue
                val id = c.optString("id").trim()
                val name = c.optString("name").trim()
                if (id.isBlank() || name.isBlank()) continue
                result += OnlineCharacter(gameId, name, c.optString("slug", name.lowercase()), c.optBoolean("announced", false), "https://raw.githubusercontent.com/sweeety601/G-Codus/main/images/$id.webp", c.optInt("rarity", 0), c.optString("element", ""), id)
            }
        }
        return result.distinctBy { it.id }
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 7000
        connection.readTimeout = 10000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "G-Codus/1.0")
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
'''
DB.write_text(NEW_DB, encoding="utf-8")
s = MAIN.read_text(encoding="utf-8")
start = s.index("    private fun trackingGrid(gameId: String?, query: String?): View {")
end = s.index("    private fun trackingCharacterCell(character: TrackedCharacter): View {", start)
new_grid = r'''    private fun trackingGrid(gameId: String?, query: String?): View {
        val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val games = if (gameId == null) gameMeta else gameMeta.filter { it.id == gameId }
        val normalizedQuery = query?.trim()?.lowercase().orEmpty()
        val entries = games.flatMap { meta ->
            onlineCharacters.filter { it.gameId == meta.id }
                .filterNot { isForbiddenTrackingCharacter(meta.id, it.name, it.id) }
                .filterNot { isMainProtagonist(meta.id, it.id, it.name) }
                .filter { normalizedQuery.isBlank() || it.name.lowercase().contains(normalizedQuery) }
                .map { online -> TrackedCharacter(meta.id, meta.name, canonicalCharacterDisplayName(meta.id, online.name), online.id + ".webp", online.element, online.rarity) }
        }.distinctBy { it.gameId + "|" + it.file }
            .sortedWith(compareByDescending<TrackedCharacter> { isTracked(it.gameId, it.file) }.thenBy { it.gameName }.thenBy { it.name.lowercase() })
        if (entries.isEmpty()) {
            holder.addView(emptyCard(if (normalizedQuery.isBlank()) "Персонажей пока нет" else "Ничего не найдено"))
            return holder
        }
        var row: LinearLayout? = null
        entries.forEachIndexed { index, character ->
            if (index % 3 == 0) {
                row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
                holder.addView(row, LinearLayout.LayoutParams(-1, -2))
            }
            val cell = trackingCharacterCell(character)
            animateReveal(cell, index)
            row?.addView(cell, LinearLayout.LayoutParams(0, dp(194), 1f).apply { marginStart = if (index % 3 == 0) 0 else dp(3); marginEnd = dp(3); bottomMargin = dp(8) })
            if (index == entries.lastIndex && (index + 1) % 3 != 0) {
                repeat(3 - ((index + 1) % 3)) { row?.addView(Space(this), LinearLayout.LayoutParams(0, dp(194), 1f).apply { marginStart = dp(3); marginEnd = dp(3); bottomMargin = dp(8) }) }
            }
        }
        return holder
    }

'''
s = s[:start] + new_grid + s[end:]
start = s.index("    private fun loadTrackingPortrait(image: ImageView, file: String, gameId: String) {")
end = s.index("    private fun refreshCharacterDatabaseInBackground() {", start)
s = s[:start] + r'''    private fun loadTrackingPortrait(image: ImageView, file: String, gameId: String) {
        image.setImageDrawable(null)
        val id = file.removeSuffix(".webp").trim()
        if (id.isBlank()) return
        loadRemotePortrait(image, listOf("https://raw.githubusercontent.com/sweeety601/G-Codus/main/images/$id.webp"))
    }

''' + s[end:]
start = s.index("    private fun trackedCharacterName(gameId: String, file: String): String {")
end = s.index("    private fun trackedIdentityKey(gameId: String, file: String): String =", start)
s = s[:start] + r'''    private fun trackedCharacterName(gameId: String, file: String): String {
        val id = file.removeSuffix(".webp")
        return onlineCharacters.firstOrNull { it.gameId == gameId && it.id == id }?.name ?: canonicalCharacterDisplayName(gameId, characterDisplayName(file))
    }

''' + s[end:]
start = s.index("    private fun trackedIdentityKey(gameId: String, file: String): String =")
end = s.index("    private fun trackingKey(gameId: String, file: String) =", start)
s = s[:start] + r'''    private fun trackedIdentityKey(gameId: String, file: String): String = "tracked_v3_" + gameId + "_" + file.removeSuffix(".webp")

''' + s[end:]
start = s.index("    private fun canonicalPreferredTrackingFile(gameId: String, file: String): String {")
end = s.index("    private fun trackingKey(gameId: String, file: String) =", start)
s = s[:start] + r'''    private fun canonicalPreferredTrackingFile(gameId: String, file: String): String = file

''' + s[end:]
s = s.replace('data class TrackedCharacter(val gameId: String, val gameName: String, val name: String, val file: String)', 'data class TrackedCharacter(val gameId: String, val gameName: String, val name: String, val file: String, val element: String = "", val rarity: Int = 0)')
MAIN.write_text(s, encoding="utf-8")
print("Character repository migration applied")
