package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Online-only banner feed.
 *
 * The APK contains no banner schedule, dates or character lineup. It downloads
 * the current remote feed and normalizes it into the format used by MainActivity.
 */
object BannerSource {
    private const val FEED_URL =
        "https://raw.githubusercontent.com/sweeety601/G-Codus/main/data/banner_feed.json"

    fun fetchNormalized(context: Context): String {
        val source = fetch(FEED_URL)
        val root = JSONObject(source)

        // MainActivity consumes a normalized games array. The remote feed
        // uses a named games object, so always normalize it here.
        val sourceGamesObject = root.optJSONObject("games")
        val sourceGamesArray = root.optJSONArray("games")
        if (sourceGamesObject == null && sourceGamesArray == null) {
            throw IllegalStateException("Banner feed has no games")
        }

        val games = JSONArray()
        val definitions = listOf(
            "genshin" to "Genshin Impact",
            "wuwa" to "Wuthering Waves",
            "zzz" to "Zenless Zone Zero"
        )

        for ((id, name) in definitions) {
            val sourceGame = when {
                sourceGamesObject != null -> sourceGamesObject.optJSONObject(name)
                else -> {
                    var found: JSONObject? = null
                    for (i in 0 until sourceGamesArray!!.length()) {
                        val candidate = sourceGamesArray.optJSONObject(i) ?: continue
                        val candidateName = candidate.optString("name")
                        val candidateId = candidate.optString("id")
                        if (candidateName.equals(name, true) || candidateId.equals(id, true)) {
                            found = candidate
                            break
                        }
                    }
                    found
                }
            } ?: continue
            val current = normalizePhase(sourceGame.optJSONArray("current")?.optJSONObject(0))
            val next = normalizePhase(sourceGame.optJSONArray("next")?.optJSONObject(0))
            val upcoming = JSONArray()
            sourceGame.optJSONArray("upcoming")?.let { arr ->
                for (j in 0 until arr.length()) {
                    val phase = arr.optJSONObject(j) ?: continue
                    upcoming.put(normalizePhase(phase))
                }
            }

            games.put(
                JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("current", current)
                    .put("next", next)
                    .put("upcoming", upcoming)
            )
        }

        return JSONObject()
            .put("version", root.optInt("version", 1))
            .put("generated_at", root.optString("generated_at"))
            .put("source", root.optString("source", "online"))
            .put("games", games)
            .toString()
    }

    private fun normalizePhase(source: JSONObject?): JSONObject {
        if (source == null) {
            return JSONObject()
                .put("version", "")
                .put("start", JSONObject.NULL)
                .put("end", JSONObject.NULL)
                .put("five_star", JSONArray())
                .put("four_star", JSONArray())
                .put("unconfirmed", true)
                .put("source_status", "unconfirmed")
        }

        val characters = copyArray(source.optJSONArray("characters"))
        val fourStars = copyArray(
            source.optJSONArray("four_star")
                ?: source.optJSONArray("fourStars")
        )

        val explicitUnconfirmed = source.optBoolean("unconfirmed", false)
        val explicitStatus = source.optString("source_status")
        val status = when {
            explicitUnconfirmed -> "unconfirmed"
            explicitStatus.equals("confirmed", true) -> "confirmed"
            explicitStatus.equals("unconfirmed", true) -> "unconfirmed"
            source.optString("status").equals("live", true) -> "confirmed"
            else -> explicitStatus.ifBlank { "confirmed" }
        }

        return JSONObject()
            .put("version", source.optString("phase", source.optString("version")))
            .put("start", source.optString("start_date").ifBlank {
                source.optString("start").ifBlank { JSONObject.NULL.toString() }
            })
            .put("end", source.optString("end_date").ifBlank {
                source.optString("end").ifBlank { JSONObject.NULL.toString() }
            })
            .put("five_star", characters)
            .put("four_star", fourStars)
            .put("official_source", source.optString("official_source"))
            .put("secondary_source", source.optString("secondary_source"))
            .put("source_status", status)
            .put("unconfirmed", status.equals("unconfirmed", true))
    }

    private fun copyArray(source: JSONArray?): JSONArray {
        if (source == null) return JSONArray()
        val result = JSONArray()
        for (i in 0 until source.length()) result.put(source.optString(i))
        return result
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 20000
        connection.requestMethod = "GET"
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "G-Codus/1.0")
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP " + connection.responseCode)
        }
        return connection.inputStream.use { it.bufferedReader().readText() }
    }
}
