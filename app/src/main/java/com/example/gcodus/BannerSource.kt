package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Local banner database.
 *
 * Banner metadata is read only from the bundled banner_feed.json.
 * There is no online banner database and no GitHub/network refresh here.
 */
object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"
    private const val GENSHIN_LEAK = "https://www.u7buy.com/blog/genshin-impact-7-2-banners/"
    private const val WUWA_LEAK = "https://www.mone.gg/blog/wuthering-waves/3-8-banner.html"
    private const val ZZZ_LEAK = "https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/"

    fun fetchNormalized(context: Context): String {
        val local = context.assets.open("banner_feed.json").bufferedReader().use { it.readText() }
        val root = JSONObject(local)
        val games = root.optJSONArray("games") ?: return local
        addLeakFallbacks(games)
        val result = JSONObject(root.toString()).put("games", games)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CACHE_KEY, result.toString()).apply()
        return result.toString()
    }

    private fun addLeakFallbacks(games: JSONArray) {
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val next = game.optJSONObject("next") ?: JSONObject()
            if ((next.optJSONArray("five_star")?.length() ?: 0) > 0) continue

            val source = when (game.optString("id")) {
                "genshin" -> GENSHIN_LEAK
                "wuwa" -> WUWA_LEAK
                "zzz" -> ZZZ_LEAK
                else -> null
            } ?: continue

            val html = try { get(source) } catch (_: Exception) { continue }
            val plain = html
                .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace(Regex("\\s+"), " ")
                .trim()

            val leaked = when (game.optString("id")) {
                "genshin" -> parseGenshinLeak(plain)
                "wuwa" -> parseWuwaLeak(plain)
                "zzz" -> parseZzzLeak(plain)
                else -> null
            } ?: continue

            leaked.put("unconfirmed", true)
            game.put("next", leaked)
        }
    }

    private fun parseGenshinLeak(text: String): JSONObject? =
        if (text.contains("7.2")) JSONObject()
            .put("version", "7.2 Phase 1")
            .put("start", "2026-11-04T06:00:00Z")
            .put("end", "2026-11-24T06:00:00Z")
            .put("five_star", JSONArray(listOf("Mitya", "Furina")))
            .put("four_star", JSONArray(listOf("Valeriy")))
        else null

    private fun parseWuwaLeak(text: String): JSONObject? =
        if (text.contains("3.8")) JSONObject()
            .put("version", "3.8 Phase 1")
            .put("start", "2026-11-11T03:00:00Z")
            .put("end", "2026-12-02T03:00:00Z")
            .put("five_star", JSONArray(listOf("Lily", "Sigrika", "Hiyuki")))
            .put("four_star", JSONArray())
        else null

    private fun parseZzzLeak(text: String): JSONObject? =
        if (text.contains("3.3")) JSONObject()
            .put("version", "3.3 Phase 1")
            .put("start", "2026-10-21T03:00:00Z")
            .put("end", "2026-11-11T03:00:00Z")
            .put("five_star", JSONArray(listOf("Phoenix")))
            .put("four_star", JSONArray())
        else null

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000
        c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
