package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

/**
 * Banner feed source.
 *
 * Banner lineup data remains local (banner_feed.json). BannerHistory is used only
 * to calculate the Debut / N-th rerun label for characters that are already in
 * the local banner feed. Upcoming pickups are intentionally NOT counted by
 * BannerHistory, so a future banner never increments its own historical count.
 */
object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"
    private const val GENSHIN_LEAK = "https://www.u7buy.com/blog/genshin-impact-7-2-banners/"
    private const val WUWA_LEAK = "https://www.mone.gg/blog/wuthering-waves/3-8-banner.html"
    private const val ZZZ_LEAK = "https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/"
    private const val BANNER_HISTORY = "https://bannerhistory.app/en/"

    fun fetchNormalized(context: Context): String {
        val local = context.assets.open("banner_feed.json").bufferedReader().use { it.readText() }
        val root = JSONObject(local)
        val games = root.optJSONArray("games") ?: return local

        // Keep the local banner lineup as the source of truth. Only the rerun
        // label is calculated online from BannerHistory.
        updateRerunLabels(games)
        addLeakFallbacks(games)
        // Leak fallback can add a future character after the first calculation,
        // so calculate its label too. Existing labels are harmlessly replaced.
        updateRerunLabels(games)

        val result = JSONObject(root.toString()).put("games", games)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CACHE_KEY, result.toString()).apply()
        return result.toString()
    }

    private fun updateRerunLabels(games: JSONArray) {
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val gameId = game.optString("id")
            val current = game.optJSONObject("current")
            val next = game.optJSONObject("next")
            if (current != null) updatePhaseLabels(gameId, current, isFuture = false)
            if (next != null) updatePhaseLabels(gameId, next, isFuture = true)
        }
    }

    private fun updatePhaseLabels(gameId: String, phase: JSONObject, isFuture: Boolean) {
        val chars = phase.optJSONArray("five_star") ?: return
        val labels = JSONObject()
        for (i in 0 until chars.length()) {
            val name = chars.optString(i).trim()
            if (name.isEmpty()) continue
            val historicalPickups = fetchHistoricalPickupCount(gameId, name) ?: continue
            // BannerHistory excludes upcoming pickups. Therefore:
            // future phase: N prior pickups -> N-th rerun, or debut when N=0.
            // live phase: N includes the live pickup -> (N-1)-th rerun.
            val priorPickups = if (isFuture) historicalPickups else (historicalPickups - 1).coerceAtLeast(0)
            labels.put(name, rerunLabel(priorPickups))
        }
        if (labels.length() > 0) phase.put("rerun_labels", labels)
    }

    private fun rerunLabel(priorPickups: Int): String =
        if (priorPickups <= 0) "Дебют" else "$priorPickups-й реран"

    /**
     * BannerHistory exposes one character's complete pickup record when the
     * character query parameter is supplied. We read only its explicit
     * "Event pickups (N)" count. This avoids scraping unrelated numbers from
     * the page (the old source of errors such as "164-й реран").
     */
    private fun fetchHistoricalPickupCount(gameId: String, character: String): Int? {
        val gameSlug = when (gameId) {
            "genshin" -> "genshin"
            "wuwa" -> "wuwa"
            "zzz" -> "zzz"
            else -> return null
        }
        val encoded = try {
            URLEncoder.encode(character, "UTF-8")
        } catch (_: Exception) {
            return null
        }
        val url = "$BANNER_HISTORY${gameSlug}-banners?character=$encoded"
        val html = try { get(url) } catch (_: Exception) { return null }

        // Prefer the exact labelled counter. It is deliberately bounded so a
        // broken page cannot turn an unrelated number into a rerun count.
        val match = Regex("Event\\s+pickups\\s*\\((\\d{1,2})\\)", RegexOption.IGNORE_CASE)
            .find(html) ?: return null
        return match.groupValues.getOrNull(1)?.toIntOrNull()?.takeIf { it in 0..20 }
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
        c.connectTimeout = 8000
        c.readTimeout = 10000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
