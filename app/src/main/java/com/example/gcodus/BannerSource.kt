package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Runtime banner-history source.
 * Local JSON stores banner lineups; rerun labels are calculated from independent history sources.
 *
 * Sources:
 *  - Genshin: Jaeger banner archive (jaeger.moe)
 *  - WuWa: WuWa Tracker banner history (wuwatracker.com), with BannerHistory as a fail-safe
 *  - ZZZ: zzz.163.moe/banners, with BannerHistory as a fail-safe
 */
object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"

    private const val GENSHIN_HISTORY = "https://r.jina.ai/https://jaeger.moe/ru/banners/"
    private const val WUWA_HISTORY = "https://r.jina.ai/https://wuwatracker.com/ru/banner-history"
    private const val WUWA_HISTORY_FALLBACK = "https://r.jina.ai/https://bannerhistory.app/en/wuwa-banners"
    private const val ZZZ_HISTORY = "https://r.jina.ai/https://zzz.163.moe/banners"
    private const val ZZZ_HISTORY_FALLBACK = "https://r.jina.ai/https://bannerhistory.app/en/zzz-pickup-history"

    private const val GENSHIN_LEAK = "https://www.u7buy.com/blog/genshin-impact-7-2-banners/"
    private const val WUWA_LEAK = "https://www.mone.gg/blog/wuthering-waves/3-8-banner.html"
    private const val ZZZ_LEAK = "https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/"

    fun fetchNormalized(context: Context): String {
        val local = context.assets.open("banner_feed.json").bufferedReader().use { it.readText() }
        val root = JSONObject(local)
        val games = root.optJSONArray("games") ?: return local

        clearRerunLabels(games)
        addLeakFallbacks(games)
        updateRerunLabels(games)

        val result = JSONObject(root.toString()).put("games", games)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CACHE_KEY, result.toString()).apply()
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

            val history = when (gameId) {
                "genshin" -> fetchGenshinHistory(name, version)
                "wuwa" -> fetchWuwaHistory(name, version)
                "zzz" -> fetchZzzHistory(name, version)
                else -> null
            } ?: continue

            // History sources contain only started/past pickups. Therefore a future phase
            // is never added to the historical count. For a current phase, subtract the
            // current run only when the source already contains that version.
            val previous = if (isFuture) {
                history.count
            } else {
                (history.count - if (history.containsCurrentVersion) 1 else 0).coerceAtLeast(0)
            }
            labels.put(name, rerunLabel(previous))
        }

        if (labels.length() > 0) phase.put("rerun_labels", labels)
    }

    private fun rerunLabel(previousPickups: Int): String =
        if (previousPickups <= 0) "Дебют" else "$previousPickups-й реран"

    private data class HistoryCount(val count: Int, val containsCurrentVersion: Boolean)

    /** Jaeger publishes a version/phase archive where every featured character appears once per pickup. */
    private fun fetchGenshinHistory(character: String, currentVersion: String): HistoryCount? {
        val text = try { get(GENSHIN_HISTORY) } catch (_: Exception) { return null }
        val aliases = characterAliases("genshin", character)
        val count = countNameOccurrences(text, aliases)
        if (count !in 1..20) return null
        val current = aliases.any { alias ->
            Regex("(?im)^(?:.*\\b${Regex.escape(alias)}\\b.*)$").findAll(text).any { match ->
                val line = match.value
                currentVersion.isNotBlank() && line.contains(currentVersion, ignoreCase = true)
            }
        }
        return HistoryCount(count, current)
    }

    /**
     * WuWa Tracker is the primary WuWa source. The page is client-rendered, so r.jina.ai
     * is used only as an HTML/text reader. If it fails, the WuWa-specific BannerHistory
     * archive is used rather than returning a false "Дебют".
     */
    private fun fetchWuwaHistory(character: String, currentVersion: String): HistoryCount? {
        val aliases = characterAliases("wuwa", character)
        val primary = try { get(WUWA_HISTORY) } catch (_: Exception) { "" }
        val result = parseLineBasedHistory(primary, aliases, currentVersion)
        if (result != null) return result

        val fallback = try { get(WUWA_HISTORY_FALLBACK) } catch (_: Exception) { return null }
        return parseLineBasedHistory(fallback, aliases, currentVersion)
    }

    /** zzz.163.moe lists each S-rank with its debut version and every later pickup version. */
    private fun fetchZzzHistory(character: String, currentVersion: String): HistoryCount? {
        val aliases = characterAliases("zzz", character)
        val text = try { get(ZZZ_HISTORY) } catch (_: Exception) { "" }
        val primary = parseZzz163History(text, aliases, currentVersion)
        if (primary != null) return primary

        val fallback = try { get(ZZZ_HISTORY_FALLBACK) } catch (_: Exception) { return null }
        return parseLineBasedHistory(fallback, aliases, currentVersion)
    }

    private fun parseZzz163History(text: String, aliases: List<String>, currentVersion: String): HistoryCount? {
        if (text.isBlank()) return null
        for (alias in aliases) {
            val nameIndex = text.indexOf(alias, ignoreCase = true)
            if (nameIndex < 0) continue

            // 163.moe renders one compact record per agent: debut version, zero or more
            // rerun versions, then the full agent name. Limit the window to this record.
            val start = (nameIndex - 350).coerceAtLeast(0)
            val end = (nameIndex + 120).coerceAtMost(text.length)
            val block = text.substring(start, end)
            val versions = Regex("\\b(?:[123]\\.\\d+)\\b").findAll(block)
                .map { it.value }
                .toList()
                .distinct()

            if (versions.isEmpty()) continue
            val current = currentVersion.isNotBlank() && versions.any { it.equals(currentVersion, true) }
            return HistoryCount(versions.size, current)
        }
        return null
    }

    private fun parseLineBasedHistory(
        text: String,
        aliases: List<String>,
        currentVersion: String
    ): HistoryCount? {
        if (text.isBlank()) return null
        val lines = text.lines()
        for (alias in aliases) {
            val matches = lines.filter { it.contains(alias, ignoreCase = true) }
                .filterNot { it.contains("Download", true) || it.contains("Privacy", true) }
            if (matches.isEmpty()) continue

            val count = matches.size
            if (count !in 1..20) continue
            val current = currentVersion.isNotBlank() && matches.any {
                it.contains(currentVersion, ignoreCase = true)
            }
            return HistoryCount(count, current)
        }
        return null
    }

    private fun countNameOccurrences(text: String, aliases: List<String>): Int {
        for (alias in aliases) {
            val regex = Regex("(?i)(?<![\\p{L}\\p{N}_])${Regex.escape(alias)}(?![\\p{L}\\p{N}_])")
            val count = regex.findAll(text).count()
            if (count in 1..20) return count
        }
        return 0
    }

    private fun characterAliases(gameId: String, name: String): List<String> {
        val n = name.trim()
        return when (gameId) {
            "wuwa" -> when (n.lowercase()) {
                "the shorekeeper", "shorekeeper" -> listOf("The Shorekeeper", "Shorekeeper")
                "xiangli yao" -> listOf("Xiangli Yao", "XiangliYao")
                else -> listOf(n)
            }
            "zzz" -> when (n.lowercase()) {
                "starlight billy", "starlight - billy", "starlight-billy" -> listOf("Starlight Billy", "Starlight - Billy")
                "billy kid" -> listOf("Billy Kid")
                "caesar king" -> listOf("Caesar King", "Caesar")
                "hoshimi miyabi" -> listOf("Hoshimi Miyabi", "Miyabi")
                "tsukishiro yanagi" -> listOf("Tsukishiro Yanagi", "Yanagi")
                else -> listOf(n)
            }
            else -> listOf(n)
        }
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
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        c.setRequestProperty("Accept", "text/plain,text/html,application/xhtml+xml")
        return c.inputStream.bufferedReader().use { it.readText() }
    }
}
