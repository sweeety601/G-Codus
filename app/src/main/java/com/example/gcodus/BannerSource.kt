package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.OffsetDateTime
import java.util.regex.Pattern

/** Runtime-only rerun calculator. Banner dates/lineups always come from the bundled feed. */
object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"

    private const val GENSHIN_CHAR = "https://r.jina.ai/https://jaeger.moe/ru/characters/"
    private const val WUWA_ROSTER = "https://r.jina.ai/https://wudamage.com/banners"
    private const val ZZZ_HISTORY = "https://r.jina.ai/https://bannerhistory.app/en/zzz-pickup-history"

    private data class History(val runs: Int, val currentStarted: Boolean)

    fun fetchNormalized(context: Context): String {
        val text = context.assets.open("banner_feed.json").bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val games = root.optJSONArray("games") ?: JSONArray()
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            game.optJSONObject("current")?.remove("rerun_labels")
            game.optJSONObject("next")?.remove("rerun_labels")
            game.optJSONObject("current")?.let { enrich(game.optString("id"), it, false) }
            game.optJSONObject("next")?.let { enrich(game.optString("id"), it, true) }
        }
        val result = root.put("games", games).toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CACHE_KEY, result).apply()
        return result
    }

    private fun enrich(gameId: String, phase: JSONObject, future: Boolean) {
        val chars = phase.optJSONArray("five_star") ?: return
        val version = phase.optString("version").substringBefore(" Phase").trim()
        val labels = JSONObject()
        for (i in 0 until chars.length()) {
            val name = chars.optString(i).trim()
            if (name.isEmpty()) continue
            val h = when (gameId) {
                "genshin" -> genshin(name, version)
                "wuwa" -> wuwa(name, version)
                "zzz" -> zzz(name, version)
                else -> null
            } ?: continue
            // h.runs is the number of already-started appearances. For a live
            // phase the live appearance itself is not a previous rerun.
            val previous = if (future) h.runs else (h.runs - if (h.currentStarted) 1 else 0).coerceAtLeast(0)
            labels.put(name, if (previous == 0) "Дебют" else "$previous-й реран")
        }
        if (labels.length() > 0) phase.put("rerun_labels", labels)
    }

    private fun genshin(name: String, version: String): History? {
        val slug = slugify(name)
        val text = getOrEmpty(GENSHIN_CHAR + slug + "/")
        if (text.isBlank()) return null
        val rows = Regex("(?im)^\\s*[-*]\\s+v\\d+(?:\\.\\d+)+\\s*·.*$").findAll(text).map { it.value }.toList()
        if (rows.isEmpty()) return null
        val current = rows.any { Regex("\\bv${Pattern.quote(version)}\\b").containsMatchIn(it) }
        return History(rows.size, current)
    }

    private fun wuwa(name: String, version: String): History? {
        val text = getOrEmpty(WUWA_ROSTER)
        if (text.isBlank()) return null
        val aliases = when (name.lowercase()) {
            "the shorekeeper", "shorekeeper" -> listOf("Shorekeeper", "The Shorekeeper")
            "xiangli yao" -> listOf("Xiangli Yao", "XiangliYao")
            "lingyang" -> listOf("Lingyang")
            else -> listOf(name)
        }
        for (alias in aliases) {
            val p = Pattern.compile("(?is)\\b" + Pattern.quote(alias) + ".{0,220}?(\\d+)\\s+runs?\\s+Banners?:\\s*([^\\n\\r]+)")
            val m = p.matcher(text)
            if (m.find()) {
                val runs = m.group(1)?.toIntOrNull() ?: continue
                val versions = m.group(2).orEmpty()
                val current = Regex("\\bv${Pattern.quote(version)}\\b").containsMatchIn(versions)
                return History(runs, current)
            }
        }
        return null
    }

    private fun zzz(name: String, version: String): History? {
        val text = getOrEmpty(ZZZ_HISTORY)
        if (text.isBlank()) return null
        val aliases = when (name.lowercase()) {
            "billy kid" -> listOf("Billy Kid", "Billy")
            "starlight billy" -> listOf("Starlight Billy", "Starlight_Billy")
            "caesar king" -> listOf("Caesar King", "Caesar")
            "hoshimi miyabi" -> listOf("Hoshimi Miyabi", "Miyabi")
            else -> listOf(name)
        }
        val blocks = Regex("(?ms)^##\\s+Version\\s+.*?(?=^##\\s+Version\\s+|\\z)").findAll(text).map { it.value }.toList()
        var count = 0
        var current = false
        val now = Instant.now()
        for (block in blocks) {
            if (!aliases.any { block.contains(it, true) }) continue
            val date = Regex("(?im)^Start\\s*\\n\\s*(\\d{4}-\\d{2}-\\d{2})").find(block)?.groupValues?.getOrNull(1) ?: continue
            val start = try { OffsetDateTime.parse(date + "T00:00:00Z").toInstant() } catch (_: Exception) { continue }
            if (start.isAfter(now)) continue
            count++
            val v = Regex("(?i)^##\\s+Version\\s+(\\d+(?:\\.\\d+)*)").find(block)?.groupValues?.getOrNull(1)
            if (v == version) current = true
        }
        return if (count in 0..30) History(count, current) else null
    }

    private fun slugify(value: String): String = value.trim().lowercase()
        .replace("’", "").replace("'", "").replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "-").trim('-')

    private fun getOrEmpty(url: String): String = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 7000
        c.readTimeout = 10000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        c.inputStream.use { it.bufferedReader().readText() }
    } catch (_: Exception) { "" }
}
