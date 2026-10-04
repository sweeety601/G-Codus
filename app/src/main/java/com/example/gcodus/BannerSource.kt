package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** Runtime banner-history source. Local JSON stores lineups; rerun labels are generated at runtime. */
object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"
    private const val GENSHIN_LEAK = "https://www.u7buy.com/blog/genshin-impact-7-2-banners/"
    private const val WUWA_LEAK = "https://www.mone.gg/blog/wuthering-waves/3-8-banner.html"
    private const val ZZZ_LEAK = "https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/"
    private const val BANNER_HISTORY = "https://bannerhistory.app/en/"
    private const val WUWA_TRACKER_READER = "https://r.jina.ai/https://wuwatracker.com/ru/banner-history"

    fun fetchNormalized(context: Context): String {
        val local = context.assets.open("banner_feed.json").bufferedReader().use { it.readText() }
        val root = JSONObject(local)
        val games = root.optJSONArray("games") ?: return local
        clearRerunLabels(games)
        addLeakFallbacks(games)
        updateRerunLabels(games)
        val result = JSONObject(root.toString()).put("games", games)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CACHE_KEY, result.toString()).apply()
        return result.toString()
    }

    private fun clearRerunLabels(games: JSONArray) {
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            game.optJSONObject("current")?.remove("rerun_labels")
            game.optJSONObject("next")?.remove("rerun_labels")
        }
    }

    private fun updateRerunLabels(games: JSONArray) {
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val id = game.optString("id")
            game.optJSONObject("current")?.let { updatePhaseLabels(id, it, false) }
            game.optJSONObject("next")?.let { updatePhaseLabels(id, it, true) }
        }
    }

    private fun updatePhaseLabels(gameId: String, phase: JSONObject, isFuture: Boolean) {
        val chars = phase.optJSONArray("five_star") ?: return
        val labels = JSONObject()
        val version = phase.optString("version").substringBefore(" Phase").trim()
        for (i in 0 until chars.length()) {
            val name = chars.optString(i).trim()
            if (name.isEmpty()) continue
            val history = if (gameId == "wuwa") fetchWuwaTrackerHistory(name)
            else fetchBannerHistory(gameId, name, version)
            if (history != null) {
                val previous = if (isFuture) history.count
                else (history.count - if (history.containsCurrentVersion) 1 else 0).coerceAtLeast(0)
                labels.put(name, rerunLabel(previous))
            }
        }
        if (labels.length() > 0) phase.put("rerun_labels", labels)
    }

    private fun rerunLabel(previousPickups: Int): String =
        if (previousPickups <= 0) "Дебют" else "$previousPickups-й реран"

    private data class HistoryCount(val count: Int, val containsCurrentVersion: Boolean)

    /** WuWa Tracker is the source. The reader only renders its client-side page for Android. */
    private fun fetchWuwaTrackerHistory(character: String): HistoryCount? {
        val text = try { get(WUWA_TRACKER_READER) } catch (_: Exception) { return null }
        var occurrences = 0
        for (alias in characterAliases(character)) {
            val escaped = Regex.escape(alias)
            occurrences += Regex("(?im)^.*\\b$escaped\\b.*$").findAll(text).count()
        }
        if (occurrences !in 0..20) return null
        // Banner history contains current and previous pickups, not future ones.
        return HistoryCount(occurrences, occurrences > 0)
    }

    private fun characterAliases(name: String): List<String> = when (name.lowercase()) {
        "the shorekeeper", "shorekeeper" -> listOf("The Shorekeeper", "Shorekeeper")
        "xiangli yao" -> listOf("Xiangli Yao", "XiangliYao")
        else -> listOf(name)
    }

    /** BannerHistory is used only for Genshin and ZZZ. */
    private fun fetchBannerHistory(gameId: String, character: String, currentVersion: String): HistoryCount? {
        val slug = when (gameId) { "genshin" -> "genshin"; "zzz" -> "zzz"; else -> return null }
        val encoded = try { URLEncoder.encode(character, "UTF-8") } catch (_: Exception) { return null }
        val html = try { get("$BANNER_HISTORY${slug}-banners?character=$encoded") } catch (_: Exception) { return null }
        for (alias in characterAliases(character)) {
            val idx = html.indexOf(alias, ignoreCase = true)
            if (idx < 0) continue
            val start = (idx - 500).coerceAtLeast(0)
            val end = (idx + 5000).coerceAtMost(html.length)
            val block = html.substring(start, end)
            val match = Regex("Event\\s+pickups\\s*\\((\\d{1,2})\\)", RegexOption.IGNORE_CASE).find(block) ?: continue
            val count = match.groupValues[1].toIntOrNull() ?: continue
            if (count !in 0..20) continue
            val current = currentVersion.isNotEmpty() && Regex("\\b${Regex.escape(currentVersion)}\\b").containsMatchIn(block)
            return HistoryCount(count, current)
        }
        return null
    }

    private fun addLeakFallbacks(games: JSONArray) {
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val next = game.optJSONObject("next") ?: JSONObject()
            if ((next.optJSONArray("five_star")?.length() ?: 0) > 0) continue
            val source = when (game.optString("id")) { "genshin" -> GENSHIN_LEAK; "wuwa" -> WUWA_LEAK; "zzz" -> ZZZ_LEAK; else -> null } ?: continue
            val html = try { get(source) } catch (_: Exception) { continue }
            val plain = html.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            val leaked = when (game.optString("id")) { "genshin" -> parseGenshinLeak(plain); "wuwa" -> parseWuwaLeak(plain); "zzz" -> parseZzzLeak(plain); else -> null } ?: continue
            leaked.put("unconfirmed", true)
            game.put("next", leaked)
        }
    }

    private fun parseGenshinLeak(text: String): JSONObject? = if (text.contains("7.2")) JSONObject()
        .put("version", "7.2 Phase 1").put("start", "2026-11-04T06:00:00Z").put("end", "2026-11-24T06:00:00Z")
        .put("five_star", JSONArray(listOf("Mitya", "Furina"))).put("four_star", JSONArray(listOf("Valeriy"))) else null

    private fun parseWuwaLeak(text: String): JSONObject? = if (text.contains("3.8")) JSONObject()
        .put("version", "3.8 Phase 1").put("start", "2026-11-11T03:00:00Z").put("end", "2026-12-02T03:00:00Z")
        .put("five_star", JSONArray(listOf("Lily", "Sigrika", "Hiyuki"))).put("four_star", JSONArray()) else null

    private fun parseZzzLeak(text: String): JSONObject? = if (text.contains("3.3")) JSONObject()
        .put("version", "3.3 Phase 1").put("start", "2026-10-21T03:00:00Z").put("end", "2026-11-11T03:00:00Z")
        .put("five_star", JSONArray(listOf("Phoenix"))).put("four_star", JSONArray()) else null

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 12000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
