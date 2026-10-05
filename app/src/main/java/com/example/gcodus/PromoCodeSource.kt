package com.example.gcodus

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reads promo codes exclusively from the G-Codus repository database.
 *
 * The APK never queries third-party promo-code APIs. External sources, if
 * needed, must be used by a server-side/database update process that writes
 * data/codes_feed.json.
 */
object PromoCodeSource {
    private const val DATA_URL =
        "https://raw.githubusercontent.com/sweeety601/G-Codus/main/data/codes_feed.json"

    fun fetchJson(context: Context): String {
        val prefs = context.getSharedPreferences("g_codus", Context.MODE_PRIVATE)
        return try {
            val json = fetch(DATA_URL + "?t=" + (System.currentTimeMillis() / 600000L))
            validate(json)
            prefs.edit().putString("codes_feed_json", json).apply()
            json
        } catch (_: Exception) {
            prefs.getString("codes_feed_json", null)
                ?.takeIf { it.isNotBlank() && runCatching { validate(it) }.isSuccess }
                ?: throw IllegalStateException("Не удалось получить базу промокодов G-Codus")
        }
    }

    private fun validate(json: String) {
        val root = JSONObject(json)
        if (root.optJSONArray("active") == null && root.optJSONArray("expired") == null) {
            throw IllegalStateException("База промокодов имеет неверный формат")
        }
    }

    private fun fetch(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-cache")
            connection.setRequestProperty("User-Agent", "G-Codus/1.0")
            connection.setRequestProperty("Accept", "application/json")
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP " + connection.responseCode)
            }
            return connection.inputStream.use { it.bufferedReader().readText() }
        } finally {
            connection.disconnect()
        }
    }
}
