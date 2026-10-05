package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the G-Codus banner feed generated from the repository Excel tables.
 * Confirmed rows have priority over leak rows with the same version/phase.
 */
object BannerSource {
    private const val FEED_URL =
        "https://raw.githubusercontent.com/sweeety601/G-Codus/main/data/banner_feed.json"

    fun fetchNormalized(context: Context): String {
        return try {
            normalize(fetch(FEED_URL + "?t=" + (System.currentTimeMillis() / 600000L)))
        } catch (_: Exception) {
            loadBundledFeed(context)
        }
    }

    private fun normalize(source: String): String {
        val root = JSONObject(source)
        val sourceGamesObject = root.optJSONObject("games")
        val sourceGamesArray = root.optJSONArray("games")
        if (sourceGamesObject == null && sourceGamesArray == null) {
            throw IllegalStateException("Banner feed has no games")
        }

        val games = JSONArray()
        val definitions = listOf(
            "genshin" to "Genshin Impact",
            "wuwa" to "Wuthering Waves",
            "zzz" to "Zenless Zone Zero",
            "starrail" to "Honkai: Star Rail",
            "endfield" to "Arknights: Endfield"
        )

        for ((id, name) in definitions) {
            val sourceGame = when {
                sourceGamesObject != null -> {
                    sourceGamesObject.optJSONObject(name)
                        ?: sourceGamesObject.optJSONObject(id)
                }
                else -> {
                    var found: JSONObject? = null
                    for (i in 0 until sourceGamesArray!!.length()) {
                        val candidate = sourceGamesArray.optJSONObject(i) ?: continue
                        if (candidate.optString("name").equals(name, true) ||
                            candidate.optString("id").equals(id, true)) {
                            found = candidate
                            break
                        }
                    }
                    found
                }
            } ?: continue

            val current = normalizeArray(sourceGame.opt("current"), id, name)
            val next = normalizeArray(sourceGame.opt("next"), id, name)
            val upcoming = normalizeArray(sourceGame.opt("upcoming"), id, name)
            val history = normalizeArray(sourceGame.opt("history"), id, name)

            games.put(
                JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("current", current)
                    .put("next", next)
                    .put("upcoming", upcoming)
                    .put("history", history)
            )
        }

        if (games.length() == 0) throw IllegalStateException("Banner feed has no supported games")

        return JSONObject()
            .put("version", root.optInt("version", 1))
            .put("generated_at", root.optString("generated_at"))
            .put("source", root.optString("source", "G-Codus banner Excel tables"))
            .put("games", games)
            .toString()
    }

    private fun normalizeArray(raw: Any?, gameId: String, gameName: String): JSONArray {
        val source = when (raw) {
            is JSONObject -> JSONArray().put(raw)
            is JSONArray -> raw
            else -> JSONArray()
        }
        val result = JSONArray()
        for (i in 0 until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val phase = normalizePhase(item)
            if (phase.optString("version").isBlank()) continue
            result.put(phase)
        }
        return result
    }

    private fun normalizePhase(source: JSONObject): JSONObject {
        val characters = copyArray(
            source.optJSONArray("characters") ?: source.optJSONArray("five_star")
        )
        val fourStars = copyArray(
            source.optJSONArray("four_star") ?: source.optJSONArray("fourStars")
        )
        val status = if (source.optString("source_status").equals("confirmed", true)) {
            "confirmed"
        } else {
            "unconfirmed"
        }

        return JSONObject()
            .put("version", source.optString("phase", source.optString("version")))
            .put("start", source.optString("start").ifBlank { JSONObject.NULL.toString() })
            .put("end", source.optString("end").ifBlank { JSONObject.NULL.toString() })
            .put("five_star", characters)
            .put("four_star", fourStars)
            .put("source_status", status)
            .put("unconfirmed", status == "unconfirmed")
    }

    private fun copyArray(source: JSONArray?): JSONArray {
        if (source == null) return JSONArray()
        val result = JSONArray()
        for (i in 0 until source.length()) {
            val value = source.optString(i).trim()
            if (value.isNotBlank()) result.put(value)
        }
        return result
    }

    private fun loadBundledFeed(context: Context): String {
        val bundled = context.assets.open("banner_feed.json").use {
            it.bufferedReader().readText()
        }
        return normalize(bundled)
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 8000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "G-Codus/1.0")
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP " + connection.responseCode)
            }
            return connection.inputStream.use { it.bufferedReader().readText() }
        } finally {
            connection.disconnect()
        }
    }
}
