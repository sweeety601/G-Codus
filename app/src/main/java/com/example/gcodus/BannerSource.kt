package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime

object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"
    private const val FEED_URL = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/app/src/main/assets/banner_feed.json"

    fun fetchNormalized(context: Context): String {
        val root = JSONObject(fetch(FEED_URL) ?: context.assets.open("banner_feed.json").bufferedReader().use { it.readText() })
        val games = root.optJSONArray("games") ?: JSONArray()
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            game.optJSONObject("current")?.remove("rerun_labels")
            game.optJSONObject("next")?.remove("rerun_labels")
        }
        val result = root.put("games", games).toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CACHE_KEY, result).apply()
        return result
    }

    private fun fetch(url: String): String? {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10000
            c.readTimeout = 15000
            c.instanceFollowRedirects = true
            c.setRequestProperty("User-Agent", "G-Codus/1.0")
            if (c.responseCode !in 200..299) null else c.inputStream.use { it.bufferedReader().readText() }
        } catch (_: Exception) { null }
    }

    private fun mergeDate(old: String, date: String, end: Boolean): String {
        if (old.isBlank() || old == "null") return date + if (end) "T23:59:59" else "T00:00:00"
        return try {
            val dt = OffsetDateTime.parse(old)
            date + "T" + dt.toLocalTime() + dt.offset
        } catch (_: Exception) { date + if (end) "T23:59:59" else "T00:00:00" }
    }
}
