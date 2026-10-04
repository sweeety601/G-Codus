package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads the generated GitHub banner feed.
 *
 * The APK does not contain the banner parser. GitHub Actions builds
 * data/banner_feed.json and the app downloads that ready JSON. A bundled
 * snapshot is used only as an immediate/offline fallback so the UI can never
 * get stuck on an endless loading screen.
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

            // The app deliberately shows only the current and immediately next phase.
            val upcoming = JSONArray()

            games.put(
                JSONObject()
                    .put("id", id)
                    .put("name", name)
                    .put("current", current)
                    .put("next", next)
                    .put("upcoming", upcoming)
            )
        }

        if (games.length() == 0) throw IllegalStateException("Banner feed has no supported games")

        return JSONObject()
            .put("version", root.optInt("version", 1))
            .put("generated_at", root.optString("generated_at"))
            .put("source", root.optString("source", "online"))
            .put("games", games)
            .toString()
    }

    private fun loadBundledFeed(context: Context): String {
        val bundled = context.assets.open("banner_feed.json").use {
            it.bufferedReader().readText()
        }
        return normalize(bundled)
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

        val characters = copyArray(
            source.optJSONArray("characters")
                ?: source.optJSONArray("five_star")
        )
        val fourStars = copyArray(
            source.optJSONArray("four_star")
                ?: source.optJSONArray("fourStars")
        )

        val explicitStatus = source.optString("source_status").trim()
        val officialSource = source.optString("official_source").trim()
        val phaseStatus = source.optString("status").trim()
        val explicitUnconfirmed = source.optBoolean("unconfirmed", false)

        // Confirmation is decided by the phase itself, not by the availability
        // of Prydwen or by the game's top-level status. An official source or a
        // live phase is sufficient to mark a phase as confirmed. An explicit
        // source_status="confirmed" always wins over a stale unconfirmed flag.
        val status = when {
            explicitStatus.equals("confirmed", true) -> "confirmed"
            officialSource.isNotBlank() -> "confirmed"
            phaseStatus.equals("live", true) -> "confirmed"
            explicitStatus.equals("unconfirmed", true) -> "unconfirmed"
            explicitUnconfirmed -> "unconfirmed"
            else -> "unconfirmed"
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
            .put("official_source", officialSource)
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
