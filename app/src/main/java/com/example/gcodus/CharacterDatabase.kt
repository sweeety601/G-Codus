package com.example.gcodus

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
    val id: String = "",
    val element: String = ""
)

object CharacterDatabase {
    private const val DATA_URL = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/library/generated/characters.json"

    fun fetch(context: Context): List<OnlineCharacter> {
        val prefs = context.getSharedPreferences("g_codus", Context.MODE_PRIVATE)
        val json = try {
            get(DATA_URL + "?v=" + System.currentTimeMillis()).also {
                prefs.edit().putString("character_feed_json", it).apply()
            }
        } catch (_: Exception) {
            prefs.getString("character_feed_json", null) ?: return emptyList()
        }
        return parse(json)
    }

    fun sync(context: Context): Boolean {
        return try {
            val fresh = get(DATA_URL + "?v=" + System.currentTimeMillis())
            JSONObject(fresh).optJSONArray("games") ?: return false
            context.getSharedPreferences("g_codus", Context.MODE_PRIVATE)
                .edit().putString("character_feed_json", fresh).apply()
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun parse(json: String): List<OnlineCharacter> {
        return try {
            val root = JSONObject(json)
            val games = root.optJSONArray("games") ?: return emptyList()
            val version = root.optString("generatedAt").ifBlank { System.currentTimeMillis().toString() }
                .replace(Regex("[^A-Za-z0-9]"), "")
            buildList {
                for (g in 0 until games.length()) {
                    val game = games.getJSONObject(g)
                    val gameId = game.optString("gameId").ifBlank { game.optString("id") }
                    val chars = game.optJSONArray("characters") ?: continue
                    for (i in 0 until chars.length()) {
                        val c = chars.getJSONObject(i)
                        val id = c.optString("id").trim()
                        val name = c.optString("name").trim()
                        if (id.isBlank() || name.isBlank()) continue
                        if (isForbidden(name)) continue
                        add(OnlineCharacter(
                            gameId = gameId,
                            name = name,
                            slug = id,
                            announced = c.optBoolean("announced", false),
                            portraitUrl = imageUrl(id, version),
                            rarity = c.optInt("rarity", 0),
                            id = id,
                            element = c.optString("element")
                        ))
                    }
                }
            }.distinctBy { it.id }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun imageUrl(id: String): String = imageUrl(id, "live")

    private fun imageUrl(id: String, version: String): String =
        "https://raw.githubusercontent.com/sweeety601/G-Codus/main/images/$id.webp?v=$version"

    private fun isForbidden(name: String): Boolean {
        val n = normalize(name)
        return n == "storyteller" || n.startsWith("thestoryteller") ||
            n == "sunbringer" || n.startsWith("sunbringer")
    }

    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]+"), "")

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.useCaches = false
        connection.setRequestProperty("Cache-Control", "no-cache, no-store, max-age=0")
        connection.setRequestProperty("Pragma", "no-cache")
        connection.setRequestProperty("User-Agent", "G-Codus/1.0")
        return try {
            if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
