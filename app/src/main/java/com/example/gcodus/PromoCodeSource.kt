package com.example.gcodus

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Locale

/**
 * Direct live promo-code sources.
 *
 * The APK does not use the repository's codes_feed.json as its source of truth.
 * The JSON is downloaded from public APIs and only the last successful response
 * is cached locally for offline rendering.
 */
object PromoCodeSource {
    private val SOURCES = mapOf(
        "genshin" to listOf(
            "hoyo-codes" to "https://hoyo-codes.seria.moe/codes?game=genshin",
            "OpenGachaCodes" to "https://api.ennead.cc/codes/genshin"
        ),
        "zzz" to listOf(
            "hoyo-codes" to "https://hoyo-codes.seria.moe/codes?game=nap",
            "OpenGachaCodes" to "https://api.ennead.cc/codes/zenless"
        ),
        "wuwa" to listOf(
            "OpenGachaCodes" to "https://api.ennead.cc/codes/wuwa",
            "game-codes" to "https://game-codes.wisp.uno/codes?game=wuwa"
        ),
        "starrail" to listOf(
            "hoyo-codes" to "https://hoyo-codes.seria.moe/codes?game=hkrpg",
            "OpenGachaCodes" to "https://api.ennead.cc/codes/starrail"
        ),
        "endfield" to listOf(
            "OpenGachaCodes" to "https://api.ennead.cc/codes/endfield",
            "game-codes" to "https://game-codes.wisp.uno/codes?game=endfield"
        )
    )

    fun fetchJson(): String {
        val active = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()

        for ((game, sources) in SOURCES) {
            var gotSource = false
            for ((sourceName, url) in sources) {
                val body = try { fetch(url) } catch (_: Exception) { null } ?: continue
                val items = parseItems(body)
                if (items.isEmpty()) continue
                gotSource = true

                for (item in items) {
                    val code = item.optString("code").trim()
                    if (code.isBlank()) continue
                    val key = game + "|" + code.uppercase(Locale.US)
                    if (!seen.add(key)) continue

                    val rewards = formatRewards(
                        item.opt("rewards").takeUnless { it == null || it == JSONObject.NULL || it.toString().equals("unknown", true) }
                            ?: item.opt("reward").takeUnless { it == null || it == JSONObject.NULL || it.toString().equals("unknown", true) }
                            ?: item.opt("items").takeUnless { it == null || it == JSONObject.NULL }
                            ?: item.opt("description")
                    )
                    active += JSONObject()
                        .put("game", game)
                        .put("code", code)
                        .put("rewards", rewards)
                        .put("source", sourceName)
                        .put("expires_at", item.optString("expires_at", ""))
                        .put("source_status", item.optString("status", "ok"))
                        .put("checked_at", Instant.now().toString())
                }

                // Primary source wins when it returns usable data. Secondary
                // source remains available when primary is temporarily empty.
                if (gotSource) break
            }
        }

        if (active.isEmpty()) throw IllegalStateException("No promo-code source returned usable data")

        val root = JSONObject()
            .put("generated_at", Instant.now().toString())
            .put("source", "direct")
            .put("active", JSONArray(active.map { it }))
            .put("expired", JSONArray())
        return root.toString()
    }

    private fun parseItems(body: String): List<JSONObject> {
        val result = mutableListOf<JSONObject>()

        fun consume(value: Any?) {
            when (value) {
                is JSONArray -> for (i in 0 until value.length()) {
                    val item = value.opt(i)
                    if (item is JSONObject) result += item
                    else if (item is String && item.isNotBlank()) {
                        result += JSONObject().put("code", item)
                    }
                }
                is JSONObject -> {
                    if (value.has("code") || value.has("key")) {
                        result += value
                    } else {
                        consume(value.optJSONArray("codes"))
                        consume(value.optJSONArray("active"))
                        consume(value.optJSONArray("data"))
                        consume(value.optJSONArray("results"))
                    }
                }
            }
        }

        val trimmed = body.trim()
        if (trimmed.startsWith("[")) {
            consume(JSONArray(trimmed))
        } else {
            consume(JSONObject(trimmed))
        }
        return result
    }

    private fun formatRewards(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until value.length())
            .map { value.optString(it) }
            .filter { it.isNotBlank() }
            .joinToString(", ")
        is JSONObject -> value.keys().asSequence()
            .map { key -> key + ": " + value.optString(key) }
            .joinToString(", ")
        else -> value.toString()
    }

    private fun fetch(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000
        c.readTimeout = 20000
        c.requestMethod = "GET"
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        c.setRequestProperty("Accept", "application/json,text/plain,*/*")
        if (c.responseCode !in 200..299) {
            throw IllegalStateException("HTTP " + c.responseCode)
        }
        return c.inputStream.use { it.bufferedReader().readText() }
    }
}
