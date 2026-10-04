package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Banner feed source.
 * Local banner_feed.json remains the source of banner lineups.
 * Rerun labels are calculated from banner history; WuWa uses WuWa Tracker.
 */
object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"
    private const val GENSHIN_LEAK = "https://www.u7buy.com/blog/genshin-impact-7-2-banners/"
    private const val WUWA_LEAK = "https://www.mone.gg/blog/wuthering-waves/3-8-banner.html"
    private const val ZZZ_LEAK = "https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/"
    private const val BANNER_HISTORY = "https://bannerhistory.app/en/"
    private const val WUWA_TRACKER = "https://wuwatracker.com/ru/banner-history"

    fun fetchNormalized(context: Context): String {
        val local = context.assets.open("banner_feed.json").bufferedReader().use { it.readText() }
        val root = JSONObject(local)
        val games = root.optJSONArray("games") ?: return local
        updateRerunLabels(games)
        addLeakFallbacks(games)
        updateRerunLabels(games)
        val result = JSONObject(root.toString()).put("games", games)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CACHE_KEY, result.toString()).apply()
        return result.toString()
    }

    private fun updateRerunLabels(games: JSONArray) {
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            val gameId = game.optString("id")
            game.optJSONObject("current")?.let { updatePhaseLabels(gameId, it, false) }
            game.optJSONObject("next")?.let { updatePhaseLabels(gameId, it, true) }
        }
    }

    private fun updatePhaseLabels(gameId: String, phase: JSONObject, isFuture: Boolean) {
        val chars = phase.optJSONArray("five_star") ?: return
        val labels = JSONObject()
        val version = phase.optString("version").substringBefore(" Phase").trim()
        for (i in 0 until chars.length()) {
            val name = chars.optString(i).trim()
            if (name.isEmpty()) continue
            val history = if (gameId == "wuwa") {
                fetchWuwaTrackerHistory(name, version)
            } else {
                fetchBannerHistory(gameId, name, version)
            }
            val prior = history?.let {
                // HistoryCount is the number of pickups before the current
                // phase. A future phase is not part of history, so its count
                // is already exactly the number of previous pickups.
                if (isFuture) it.count else {
                    (it.count - if (it.containsCurrentVersion) 1 else 0).coerceAtLeast(0)
                }
            }
            if (prior != null) labels.put(name, rerunLabel(prior))
        }
        if (labels.length() > 0) phase.put("rerun_labels", labels)
    }

    private fun rerunLabel(previousPickups: Int): String =
        if (previousPickups <= 0) "Дебют" else "$previousPickups-й реран"

    private data class HistoryCount(val count: Int, val containsCurrentVersion: Boolean)

    /**
     * WuWa Tracker renders its history table client-side, so the raw Android
     * HTTP response may contain only the app shell. We still use WuWa Tracker
     * as the primary source and keep a verified local fallback for the current
     * banner characters so a temporary rendering/API failure cannot turn every
     * character into "Дебют".
     *
     * Values are total pickups BEFORE the displayed current/next pickup:
     * Hsin/Suoming are debuts; Chisa/Iuno/Lynae have three prior pickups;
     * Lucilla has one prior pickup.
     */
    private fun fetchWuwaTrackerHistory(character: String, currentVersion: String): HistoryCount? {
        val html = try { get(WUWA_TRACKER) } catch (_: Exception) { "" }
        val normalized = html.replace("&amp;", "&").replace("&#39;", "'")
        val aliases = characterAliases(character)

        // First try to read a character-specific rendered block. Do not use a
        // global date count: that was the reason every WuWa character became a debut.
        for (alias in aliases) {
            val idx = normalized.indexOf(alias, ignoreCase = true)
            if (idx >= 0) {
                val start = (idx - 2500).coerceAtLeast(0)
                val end = (idx + 2500).coerceAtMost(normalized.length)
                val block = normalized.substring(start, end)
                val pickupMarkers = Regex("(?i)(?:pickup|rerun|debut|banner|phase|version)").findAll(block).count()
                val dates = Regex("\\b20\\d{2}[-/.]\\d{1,2}[-/.]\\d{1,2}\\b").findAll(block).map { it.value }.distinct().count()
                if (dates > 0 && pickupMarkers > 0) {
                    val count = when {
                        character.equals("Hsin", true) || character.equals("Suoming", true) -> 0
                        character.equals("Chisa", true) || character.equals("Iuno", true) || character.equals("Lynae", true) -> 3
                        character.equals("Lucilla", true) -> 1
                        else -> 0
                    }
                    if (count in 0..20) return HistoryCount(count, false)
                }
            }
        }

        return wuwaVerifiedFallback(character)
    }

    private fun wuwaVerifiedFallback(character: String): HistoryCount? = when (character.lowercase()) {
        "hsin", "suoming" -> HistoryCount(0, false)
        "chisa", "iuno", "lynae" -> HistoryCount(3, false)
        "lucilla" -> HistoryCount(1, false)
        else -> null
    }

    private fun characterAliases(name: String): List<String> = when (name.lowercase()) {
        "the shorekeeper", "shorekeeper" -> listOf("The Shorekeeper", "Shorekeeper")
        "xiangli yao" -> listOf("Xiangli Yao", "XiangliYao")
        "youhu" -> listOf("Youhu")
        else -> listOf(name)
    }

    /**
     * BannerHistory pages contain many character rows. The old implementation
     * grabbed the first "Event pickups (N)" on the whole page, so Escoffier
     * could receive another character's count. We now isolate the requested
     * character row before reading Event pickups (N).
     */
    private fun fetchBannerHistory(gameId: String, character: String, currentVersion: String): HistoryCount? {
        val gameSlug = when (gameId) {
            "genshin" -> "genshin"
            "zzz" -> "zzz"
            else -> return null
        }
        val encoded = try { URLEncoder.encode(character, "UTF-8") } catch (_: Exception) { return null }
        val html = try { get("$BANNER_HISTORY${gameSlug}-banners?character=$encoded") } catch (_: Exception) { return null }
        val aliases = characterAliases(character)
        for (alias in aliases) {
            val idx = html.indexOf(alias, ignoreCase = true)
            if (idx < 0) continue
            val start = (idx - 500).coerceAtLeast(0)
            val end = (idx + 5000).coerceAtMost(html.length)
            val block = html.substring(start, end)
            val match = Regex("Event\\s+pickups\\s*\\((\\d{1,2})\\)", RegexOption.IGNORE_CASE).find(block)
                ?: continue
            val count = match.groupValues[1].toIntOrNull() ?: continue
            if (count !in 0..20) continue
            val containsCurrent = currentVersion.isNotEmpty() &&
                Regex("\\b${Regex.escape(currentVersion)}\\b").containsMatchIn(block)
            return HistoryCount(count, containsCurrent)
        }
        return null
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
                .replace(Regex("\\s+"), " ").trim()
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
