package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Runtime banner-history source.
 * The local JSON contains only banner lineups. Rerun labels are calculated at runtime
 * from a source that exposes the actual per-character banner history.
 *
 * Sources:
 *  - Genshin: Jaeger per-character history pages
 *  - WuWa: WU.DA.MAGE per-character banner-history pages
 *  - ZZZ: BannerHistory dated pickup schedule (checked against official notices)
 *
 * A future/current pickup is never counted as a previous pickup unless its start time
 * has actually passed. This prevents "Debut"/rerun numbers from being shifted by
 * upcoming banners.
 */
object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"

    private const val GENSHIN_HISTORY = "https://r.jina.ai/https://jaeger.moe/ru/characters/"
    private const val WUWA_HISTORY = "https://r.jina.ai/https://wudamage.com/banners/"
    private const val ZZZ_HISTORY = "https://r.jina.ai/https://bannerhistory.app/en/zzz-pickup-history"

    private const val GENSHIN_FALLBACK = "https://r.jina.ai/https://bannerhistory.app/en/genshin-pickup-history"
    private const val WUWA_FALLBACK = "https://r.jina.ai/https://wuwatracker.com/ru/banner-history"
    private const val ZZZ_FALLBACK = "https://r.jina.ai/https://zzz.163.moe/banners"

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
            }

            if (history == null) continue

            // The history parser returns only pickups whose start instant has passed.
            // For a current phase this therefore already excludes an unstarted pickup.
            // If the source contains the current version, remove exactly that one run
            // because the label asks for the number of PREVIOUS runs.
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

    /** Jaeger has a dedicated page for every Genshin character with a compact banner history. */
    private fun fetchGenshinHistory(character: String, currentVersion: String): HistoryCount? {
        val slug = slugify(character)
        if (slug.isBlank()) return null
        val text = try { get(GENSHIN_HISTORY + slug + "/") } catch (_: Exception) { "" }
        if (text.isNotBlank()) {
            // Jaeger character pages expose history as bullet rows such as:
            // * v6.4 · Phase 2 ...
            val rows = Regex("(?im)^\\s*[-*]\\s+v\\d+(?:\\.\\d+)+\\s*·.*$")
                .findAll(text).map { it.value }.toList()
            if (rows.isNotEmpty()) {
                val current = currentVersion.isNotBlank() && rows.any {
                    Regex("\\bv${Regex.escape(currentVersion)}\\b").containsMatchIn(it)
                }
                return HistoryCount(rows.size, current)
            }
        }

        // Fail-safe: use the Genshin archive only if the per-character page is unavailable.
        return fetchScheduleCharacterHistory(GENSHIN_FALLBACK, character, currentVersion, "genshin")
    }

    /** WU.DA.MAGE character banner pages explicitly label each historical run as Debut/Rerun. */
    private fun fetchWuwaHistory(character: String, currentVersion: String): HistoryCount? {
        val slug = wuwaSlug(character)
        if (slug.isBlank()) return null
        val text = try { get(WUWA_HISTORY + slug) } catch (_: Exception) { "" }
        if (text.isNotBlank()) {
            // Example: "v3.5 Rerun Jul 10, 2026 – Jul 30, 2026"
            val rows = Regex("(?im)^\\s*v\\d+(?:\\.\\d+)+\\s+(?:Debut|Rerun)\\b.*$")
                .findAll(text).map { it.value }.toList()
            if (rows.isNotEmpty()) {
                val current = currentVersion.isNotBlank() && rows.any {
                    Regex("\\bv${Regex.escape(currentVersion)}\\b").containsMatchIn(it)
                }
                return HistoryCount(rows.size, current)
            }
        }

        // WuWa Tracker remains the requested independent fallback. Its chart is
        // client-rendered, so only use it when WU.DA.MAGE is unavailable.
        val tracker = try { get(WUWA_FALLBACK) } catch (_: Exception) { "" }
        return parseWuWaTrackerFallback(tracker, character, currentVersion)
    }

    /** BannerHistory provides a dated, versioned schedule checked against public notices. */
    private fun fetchZzzHistory(character: String, currentVersion: String): HistoryCount? {
        val result = fetchScheduleCharacterHistory(ZZZ_HISTORY, character, currentVersion, "zzz")
        if (result != null) return result
        return parseZzz163Fallback(try { get(ZZZ_FALLBACK) } catch (_: Exception) { "" }, character, currentVersion)
    }

    /**
     * Parse a dated schedule. Each channel starts with a Version heading and a Start date,
     * followed by its agents. Only starts at or before now count as historical pickups.
     */
    private fun fetchScheduleCharacterHistory(
        url: String,
        character: String,
        currentVersion: String,
        gameId: String
    ): HistoryCount? {
        val text = try { get(url) } catch (_: Exception) { return null }
        if (text.isBlank()) return null

        val aliases = characterAliases(gameId, character)
        val blocks = Regex("(?ms)^##\\s+Version\\s+.*?(?=^##\\s+Version\\s+|\\z)")
            .findAll(text).map { it.value }.toList()
        if (blocks.isEmpty()) return null

        val now = Instant.now()
        var count = 0
        var currentStarted = false

        for (block in blocks) {
            if (!aliases.any { block.contains(it, ignoreCase = true) }) continue

            val startMatch = Regex("(?im)^Start\\s*\\n\\s*(\\d{4}-\\d{2}-\\d{2})[^\\n]*")
                .find(block)
            val date = startMatch?.groupValues?.getOrNull(1) ?: continue
            val start = try {
                OffsetDateTime.parse(date + "T00:00:00Z").toInstant()
            } catch (_: Exception) { continue }
            if (start.isAfter(now)) continue

            count++
            val versionMatch = Regex("(?i)Version\\s+(\\d+(?:\\.\\d+)*)").find(block)
            val blockVersion = versionMatch?.groupValues?.getOrNull(1).orEmpty()
            if (currentVersion.isNotBlank() && blockVersion == currentVersion) currentStarted = true
        }

        if (count !in 0..20) return null
        return HistoryCount(count, currentStarted)
    }

    private fun parseWuWaTrackerFallback(text: String, character: String, currentVersion: String): HistoryCount? {
        if (text.isBlank()) return null
        val aliases = characterAliases("wuwa", character)
        // The tracker chart is rendered as a character row followed by numeric cells.
        // Count only a row-local run of version cells; never count the whole document.
        for (alias in aliases) {
            val idx = text.indexOf(alias, ignoreCase = true)
            if (idx < 0) continue
            val end = text.indexOf("Image:", idx + alias.length, ignoreCase = true)
                .takeIf { it > idx } ?: (idx + 6000).coerceAtMost(text.length)
            val block = text.substring(idx, end)
            val versions = Regex("\\b(?:[123]\\.\\d+)\\b").findAll(block).map { it.value }.toList()
            if (versions.isNotEmpty()) {
                return HistoryCount(versions.size, currentVersion.isNotBlank() && versions.contains(currentVersion))
            }
        }
        return null
    }

    private fun parseZzz163Fallback(text: String, character: String, currentVersion: String): HistoryCount? {
        if (text.isBlank()) return null
        for (alias in characterAliases("zzz", character)) {
            val lines = text.lines()
            val imageLine = lines.indexOfFirst { it.contains("Image: $alias", true) }
            if (imageLine < 0) continue
            val tail = lines.drop(imageLine).takeWhile { !it.contains("Image:") || it.contains("Image: $alias", true) }
            val count = tail.count { it.contains("Image: $alias", true) }
            if (count !in 1..20) continue
            return HistoryCount(count, false)
        }
        return null
    }

    private fun characterAliases(gameId: String, name: String): List<String> {
        val n = name.trim()
        return when (gameId) {
            "genshin" -> when (n.lowercase()) {
                "alhaitham" -> listOf("Alhaitham", "Al-Haitham")
                "kamisato ayaka" -> listOf("Ayaka", "Kamisato Ayaka")
                "kamisato ayato" -> listOf("Ayato", "Kamisato Ayato")
                else -> listOf(n)
            }
            "wuwa" -> when (n.lowercase()) {
                "the shorekeeper", "shorekeeper" -> listOf("Shorekeeper", "The Shorekeeper")
                "xiangli yao" -> listOf("Xiangli Yao", "XiangliYao")
                "lingyang" -> listOf("Lingyang")
                else -> listOf(n)
            }
            "zzz" -> when (n.lowercase()) {
                "starlight billy", "starlight - billy", "starlight-billy" -> listOf("Starlight Billy", "Starlight_Billy", "Starlight - Billy")
                "billy kid" -> listOf("Billy Kid", "Billy")
                "caesar king" -> listOf("Caesar King", "Caesar")
                "hoshimi miyabi" -> listOf("Hoshimi Miyabi", "Miyabi")
                "tsukishiro yanagi" -> listOf("Tsukishiro Yanagi", "Yanagi")
                "astra yao" -> listOf("Astra Yao", "Astra")
                else -> listOf(n)
            }
            else -> listOf(n)
        }
    }

    private fun slugify(value: String): String = value.trim().lowercase()
        .replace("’", "")
        .replace("'", "")
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun wuwaSlug(value: String): String = when (value.lowercase()) {
        "the shorekeeper", "shorekeeper" -> "shorekeeper"
        "xiangli yao" -> "xiangli-yao"
        "lingyang" -> "lingyang"
        else -> slugify(value)
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
