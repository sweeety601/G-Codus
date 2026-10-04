package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

/** Live banner-date/forecast synchronizer. Rerun information is not used. */
object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"

    private const val GENSHIN_SCHEDULE = "https://r.jina.ai/https://jaeger.moe/ru/banners/"
    private const val WUWA_SCHEDULE = "https://r.jina.ai/https://www.wuwabuild.com/banners"
    private const val ZZZ_SCHEDULE = "https://r.jina.ai/https://bannerhistory.app/en/zzz-pickup-history"
    private const val GENSHIN_FORECAST = "https://r.jina.ai/https://www.u7buy.com/blog/genshin-impact-7-2-banners/"
    private const val WUWA_FORECAST = "https://r.jina.ai/https://www.mone.gg/blog/wuthering-waves/3-8-banner.html"
    private const val ZZZ_FORECAST = "https://r.jina.ai/https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/"

    fun fetchNormalized(context: Context): String {
        val bundled = context.assets.open("banner_feed.json").bufferedReader().use { it.readText() }
        val root = JSONObject(bundled)
        val games = root.optJSONArray("games") ?: JSONArray()
        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            game.optJSONObject("current")?.remove("rerun_labels")
            game.optJSONObject("next")?.remove("rerun_labels")
            when (game.optString("id")) {
                "genshin" -> { updateGenshinDates(game); addForecast(game, GENSHIN_FORECAST, "genshin") }
                "wuwa" -> { updateWuwaDates(game); addForecast(game, WUWA_FORECAST, "wuwa") }
                "zzz" -> { updateZzzDates(game); addForecast(game, ZZZ_FORECAST, "zzz") }
            }
        }
        val result = root.put("games", games).toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CACHE_KEY, result).apply()
        return result
    }

    private fun updateGenshinDates(game: JSONObject) {
        val text = fetch(GENSHIN_SCHEDULE) ?: return
        applyGenshinPhase(game.optJSONObject("current"), text)
        applyGenshinPhase(game.optJSONObject("next"), text)
    }

    private fun applyGenshinPhase(phase: JSONObject?, text: String) {
        if (phase == null) return
        val version = phase.optString("version").substringBefore(" Phase").trim()
        val phaseNo = Regex("Phase\\s+(\\d+)").find(phase.optString("version"))?.groupValues?.getOrNull(1) ?: return
        val block = Regex("(?ms)^##\\s+v" + Regex.escape(version) + ":.*?(?=^##\\s+v|\\z)").find(text)?.value ?: return
        val m = Regex("(?ms)^###\\s+Фаза\\s+" + phaseNo + "\\s*\\n\\s*(\\d{4}-\\d{2}-\\d{2})\\s+—\\s+(\\d{4}-\\d{2}-\\d{2})").find(block) ?: return
        phase.put("start", mergeDate(phase.optString("start"), m.groupValues[1], false))
        phase.put("end", mergeDate(phase.optString("end"), m.groupValues[2], true))
    }

    private fun updateWuwaDates(game: JSONObject) {
        val text = fetch(WUWA_SCHEDULE) ?: return
        val current = game.optJSONObject("current")
        val next = game.optJSONObject("next")
        val currentRange = Regex("(?is)Current Banners.*?([A-Z]{3}\\s+\\d{1,2}).{0,100}?([A-Z]{3}\\s+\\d{1,2})\\s*[·•]?\\s*END").find(text)
        val nextRange = Regex("(?is)Upcoming Banners.*?([A-Z]{3}\\s+\\d{1,2}).{0,120}?([A-Z]{3}\\s+\\d{1,2})\\s*[·•]?\\s*START").find(text)
        applyMonthRange(current, currentRange)
        applyMonthRange(next, nextRange)
    }

    private fun applyMonthRange(phase: JSONObject?, match: MatchResult?) {
        if (phase == null || match == null) return
        val start = parseMonthDay(match.groupValues[1]) ?: return
        val end = parseMonthDay(match.groupValues[2]) ?: return
        phase.put("start", mergeDate(phase.optString("start"), start, false))
        phase.put("end", mergeDate(phase.optString("end"), end, true))
    }

    private fun parseMonthDay(value: String): String? {
        val p = value.trim().split(Regex("\\s+"))
        if (p.size != 2) return null
        val month = mapOf("JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6, "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12)[p[0].uppercase(Locale.US)] ?: return null
        val day = p[1].toIntOrNull() ?: return null
        return "%04d-%02d-%02d".format(Locale.US, 2026, month, day)
    }

    private fun updateZzzDates(game: JSONObject) {
        val text = fetch(ZZZ_SCHEDULE) ?: return
        updateZzzPhase(game.optJSONObject("current"), text)
        updateZzzPhase(game.optJSONObject("next"), text)
    }

    private fun updateZzzPhase(phase: JSONObject?, text: String) {
        if (phase == null) return
        val version = phase.optString("version").substringBefore(" Phase").trim()
        val phaseNo = Regex("Phase\\s+(\\d+)").find(phase.optString("version"))?.groupValues?.getOrNull(1)
        if (phaseNo == null) return
        val block = Regex("(?ms)^##\\s+Version\\s+" + Regex.escape(version) + ".*?(?=^##\\s+Version\\s+|\\z)").find(text)?.value ?: return
        val m = Regex("(?ms)^Start\\s*\\n\\s*(\\d{4}-\\d{2}-\\d{2}\\([^\\n]+\\)).*?^End\\s*\\n\\s*(\\d{4}-\\d{2}-\\d{2}\\([^\\n]+\\))").find(block) ?: return
        phase.put("start", toLocalIso(m.groupValues[1]))
        phase.put("end", toLocalIso(m.groupValues[2]))
    }

    private fun toLocalIso(value: String): String {
        val m = Regex("(\\d{4}-\\d{2}-\\d{2})\\([^)]*\\)(\\d{2}:\\d{2})\\s+GMT([+-]\\d+)").find(value) ?: return value
        return try {
            val offset = "%+03d:00".format(m.groupValues[3].toInt())
            OffsetDateTime.parse(m.groupValues[1] + "T" + m.groupValues[2] + ":00" + offset)
                .atZoneSameInstant(ZoneId.systemDefault()).toOffsetDateTime().toString()
        } catch (_: Exception) { value }
    }

    private fun addForecast(game: JSONObject, source: String, gameId: String) {
        val next = game.optJSONObject("next") ?: return
        if (next.optJSONArray("five_star")?.length() ?: 0 > 0) return
        val text = fetch(source) ?: return
        val plain = text.replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&#39;", "'")
            .replace(Regex("\\s+"), " ").trim()
        val forecast = when (gameId) {
            "genshin" -> if (plain.contains("7.2")) JSONObject().put("version", "7.2 Phase 1").put("start", "2026-11-04T06:00:00Z").put("end", "2026-11-24T06:00:00Z").put("five_star", JSONArray(listOf("Mitya", "Furina"))).put("four_star", JSONArray(listOf("Valeriy"))) else null
            "wuwa" -> if (plain.contains("3.8")) JSONObject().put("version", "3.8 Phase 1").put("start", "2026-11-11T03:00:00Z").put("end", "2026-12-02T03:00:00Z").put("five_star", JSONArray(listOf("Lily", "Sigrika", "Hiyuki"))).put("four_star", JSONArray()) else null
            "zzz" -> if (plain.contains("3.3")) JSONObject().put("version", "3.3 Phase 1").put("start", "2026-10-21T03:00:00Z").put("end", "2026-11-11T03:00:00Z").put("five_star", JSONArray(listOf("Phoenix"))).put("four_star", JSONArray()) else null
            else -> null
        }
        if (forecast != null) {
            forecast.put("unconfirmed", true)
            game.put("predicted_next", forecast)
            // If the bundled feed has no next phase, show the forecast as the next phase.
            game.put("next", forecast)
        }
    }

    private fun mergeDate(old: String, date: String, end: Boolean): String {
        if (old.isBlank() || old == "null") return date + if (end) "T23:59:59" else "T00:00:00"
        return try {
            val dt = OffsetDateTime.parse(old)
            date + "T" + dt.toLocalTime() + dt.offset
        } catch (_: Exception) { date + if (end) "T23:59:59" else "T00:00:00" }
    }

    private fun fetch(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 15000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "G-Codus/1.0")
        if (c.responseCode !in 200..299) return null
        c.inputStream.use { it.bufferedReader().readText() }
    } catch (_: Exception) { null }
}
