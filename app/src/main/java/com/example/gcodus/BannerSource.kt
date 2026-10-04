package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Live banner-date/forecast synchronizer. */
object BannerSource {
    private const val PREFS = "g_codus"
    private const val CACHE_KEY = "banner_feed"

    // The repository's banner synchronizer writes its canonical source snapshot here.
    private const val FEED_URL = "https://raw.githubusercontent.com/sweeety601/G-Codus/main/data/banner_feed.json"

    private const val GENSHIN_HISTORY = "https://r.jina.ai/https://jaeger.moe/ru/banners/"
    private const val GENSHIN_SCHEDULE = "https://r.jina.ai/https://www.genshin-build.com/banners"
    private const val WUWA_SCHEDULE = "https://r.jina.ai/https://www.wuwabuild.com/banners"
    private const val ZZZ_HISTORY = "https://r.jina.ai/https://bannerhistory.app/en/zzz-pickup-history"
    private const val ZZZ_FORECAST = "https://r.jina.ai/https://timesaver.gg/blog/zzz-3-3"
    private const val ZZZ_FORECAST_FALLBACK = "https://r.jina.ai/https://www.u7buy.com/blog/zenless-zone-zero-3-3-banners/"

    fun fetchNormalized(context: Context): String {
        val fallback = context.assets.open("banner_feed.json").bufferedReader().use { it.readText() }
        val source = fetch(FEED_URL)
        val root = if (source != null) normalizeSourceFeed(JSONObject(source), JSONObject(fallback)) else JSONObject(fallback)
        val games = root.optJSONArray("games") ?: JSONArray()

        for (i in 0 until games.length()) {
            val game = games.optJSONObject(i) ?: continue
            when (game.optString("id")) {
                "genshin" -> syncGenshin(game)
                "wuwa" -> syncWuwa(game)
                "zzz" -> syncZzz(game)
            }
        }

        val result = root.put("games", games).toString()
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(CACHE_KEY, result).apply()
        return result
    }

    /** Converts data/banner_feed.json into the app's normalized array format. */
    private fun normalizeSourceFeed(source: JSONObject, fallback: JSONObject): JSONObject {
        if (source.optJSONObject("games") == null) return source
        val sourceGames = source.optJSONObject("games") ?: return fallback
        val fallbackGames = fallback.optJSONArray("games") ?: JSONArray()
        val normalizedGames = JSONArray()

        for (i in 0 until fallbackGames.length()) {
            val base = JSONObject(fallbackGames.getJSONObject(i).toString())
            val gameName = base.optString("name")
            val srcGame = sourceGames.optJSONObject(gameName) ?: continue

            applySourcePhase(base.optJSONObject("current"), srcGame.optJSONArray("current")?.optJSONObject(0))
            applySourcePhase(base.optJSONObject("next"), srcGame.optJSONArray("next")?.optJSONObject(0))
            normalizedGames.put(base)
        }

        return JSONObject(fallback.toString()).put("games", normalizedGames)
    }

    private fun applySourcePhase(target: JSONObject?, source: JSONObject?) {
        if (target == null || source == null) return

        val phase = source.optString("phase").takeIf { it.isNotBlank() }
        if (phase != null) target.put("version", phase)

        val chars = source.optJSONArray("characters")
        if (chars != null && chars.length() > 0) target.put("five_star", JSONArray(chars.toString()))

        val startDate = source.optString("start_date").takeIf { it.isNotBlank() && it != "null" }
        val endDate = source.optString("end_date").takeIf { it.isNotBlank() && it != "null" }
        if (startDate != null) target.put("start", mergeDate(target.optString("start"), startDate, false))
        if (endDate != null) target.put("end", mergeDate(target.optString("end"), endDate, true))

        source.optString("official_source").takeIf { it.isNotBlank() && it != "null" }?.let {
            target.put("official_source", it)
        }

        source.optString("status").takeIf { it.isNotBlank() && it != "null" }?.let {
            target.put("source_status", it)
        }
    }

    private fun syncGenshin(game: JSONObject) {
        val history = fetch(GENSHIN_HISTORY)
        history?.let { updateGenshinPhase(game.optJSONObject("current"), it) }

        val schedule = fetch(GENSHIN_SCHEDULE)
        if (schedule != null) {
            updateGenshinLineupFromSchedule(game.optJSONObject("current"), game.optJSONObject("next"), schedule)
            updateGenshinNextFromSchedule(game.optJSONObject("next"), schedule)
        }

        // A next phase is treated as unconfirmed only when the canonical source
        // does not provide an official announcement link.
        game.optJSONObject("next")?.let { next ->
            val official = next.optString("official_source")
            // A specific official announcement is confirmation. A generic
            // HoYoLAB news feed is not enough to mark a banner as confirmed.
            val confirmed = official.contains("/news/", true) || official.contains("/news/detail/", true)
            next.put("unconfirmed", !confirmed)
        }
    }

    private fun updateGenshinPhase(phase: JSONObject?, text: String) {
        if (phase == null) return
        val version = phase.optString("version").substringBefore(" Phase").trim()
        val phaseNo = Regex("(?i)Phase\\s+(\\d+)")
            .find(phase.optString("version"))?.groupValues?.getOrNull(1) ?: return

        val block = Regex("(?ms)^##\\s+v" + Regex.escape(version) + ".*?(?=^##\\s+v|\\z)")
            .find(text)?.value ?: return

        val dates = Regex(
            "(?ms)^###\\s+Фаза\\s+" + phaseNo +
                "\\s*\\n\\s*(\\d{4}-\\d{2}-\\d{2})\\s+—\\s+(\\d{4}-\\d{2}-\\d{2})"
        ).find(block) ?: return

        putDateRange(phase, dates.groupValues[1], dates.groupValues[2])
    }

    private fun updateGenshinLineupFromSchedule(current: JSONObject?, next: JSONObject?, text: String) {
        if (current != null && text.contains("Vesna", true) && text.contains("Vodyanitsa", true)) {
            current.put("five_star", JSONArray(listOf("Vesna", "Vodyanitsa")))
        }
        if (next != null && text.contains("Escoffier", true) && text.contains("Skirk", true)) {
            next.put("five_star", JSONArray(listOf("Escoffier", "Skirk")))
        }
    }

    private fun updateGenshinNextFromSchedule(phase: JSONObject?, text: String) {
        if (phase == null) return
        val m = Regex(
            "(?is)Version\\s+7\\.1\\s+Phase\\s+2.{0,1500}?" +
                "Opens\\s+([A-Z][a-z]+\\s+\\d{1,2},\\s+\\d{4}).{0,120}?" +
                "Ends\\s+([A-Z][a-z]+\\s+\\d{1,2},\\s+\\d{4})"
        ).find(text) ?: return

        val start = parseEnglishDate(m.groupValues[1]) ?: return
        val end = parseEnglishDate(m.groupValues[2]) ?: return
        putDateRange(phase, start.toString(), end.toString())
    }

    private fun syncWuwa(game: JSONObject) {
        val text = fetch(WUWA_SCHEDULE) ?: return
        val current = game.optJSONObject("current")
        val next = game.optJSONObject("next")

        val currentMatch = Regex(
            "(?is)Current Banners.{0,3000}?([A-Z]{3})\\s+(\\d{1,2})\\s*([A-Z]{3})\\s+(\\d{1,2})\\s*·\\s*END"
        ).find(text)
        val nextMatch = Regex(
            "(?is)Upcoming Banners.{0,3000}?([A-Z]{3})\\s+(\\d{1,2})\\s*([A-Z]{3})\\s+(\\d{1,2})\\s*·\\s*START"
        ).find(text)

        if (current != null && text.contains("Hsin", true) && text.contains("Chisa", true) && text.contains("Iuno", true)) {
            current.put("five_star", JSONArray(listOf("Hsin", "Chisa", "Iuno")))
        }
        if (next != null && text.contains("Suoming", true) && text.contains("Lynae", true) && text.contains("Lucilla", true)) {
            next.put("five_star", JSONArray(listOf("Suoming", "Lynae", "Lucilla")))
            next.put("unconfirmed", false)
        }

        currentMatch?.let {
            putMonthRange(current, it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4])
        }
        nextMatch?.let {
            putMonthRange(next, it.groupValues[1], it.groupValues[2], it.groupValues[3], it.groupValues[4])
        }
    }

    private fun putMonthRange(
        phase: JSONObject?,
        startMonth: String,
        startDay: String,
        endMonth: String,
        endDay: String
    ) {
        if (phase == null) return
        val start = parseMonthDay(startMonth, startDay) ?: return
        val end = parseMonthDay(endMonth, endDay) ?: return
        putDateRange(phase, start.toString(), end.toString())
    }

    private fun parseMonthDay(monthText: String, dayText: String): LocalDate? {
        val month = mapOf(
            "JAN" to 1, "FEB" to 2, "MAR" to 3, "APR" to 4, "MAY" to 5, "JUN" to 6,
            "JUL" to 7, "AUG" to 8, "SEP" to 9, "OCT" to 10, "NOV" to 11, "DEC" to 12
        )[monthText.uppercase(Locale.US)] ?: return null
        val day = dayText.toIntOrNull() ?: return null
        return LocalDate.of(2026, month, day)
    }

    private fun syncZzz(game: JSONObject) {
        val history = fetch(ZZZ_HISTORY)
        history?.let { updateZzzCurrent(game.optJSONObject("current"), it) }

        val next = game.optJSONObject("next") ?: return
        if ((next.optJSONArray("five_star")?.length() ?: 0) > 0) return

        val forecast = fetch(ZZZ_FORECAST) ?: fetch(ZZZ_FORECAST_FALLBACK)
        if (forecast != null && forecast.contains("Phoenix", true)) {
            next.put("version", "3.3 Phase 1")
            next.put("start", "2026-10-21T06:00:00+08:00")
            next.put("end", "2026-11-11T05:59:59+08:00")
            next.put("five_star", JSONArray(listOf("Phoenix")))
            next.put("four_star", JSONArray())
            next.put("unconfirmed", true)
        }
    }

    private fun updateZzzCurrent(phase: JSONObject?, text: String) {
        if (phase == null) return
        val version = phase.optString("version").substringBefore(" Phase").trim()
        val phaseNo = Regex("(?i)Phase\\s+(\\d+|I|II)")
            .find(phase.optString("version"))?.groupValues?.getOrNull(1) ?: return

        val phaseTag = if (phaseNo == "1") "I" else if (phaseNo == "2") "II" else phaseNo

        val block = Regex(
            "(?ms)^##\\s+Version\\s+" + Regex.escape(version) +
                ".*?\\[Phase\\s+(?:" + Regex.escape(phaseNo) + "|" + Regex.escape(phaseTag) +
                ")\\].*?(?=^##\\s+Version\\s+|\\z)"
        ).find(text)?.value ?: return

        val dates = Regex(
            "(?ms)^Start\\s*\\n\\s*(\\d{4}-\\d{2}-\\d{2}\\([^\\n]+\\))\\s*\\n.*?" +
                "^End\\s*\\n\\s*(\\d{4}-\\d{2}-\\d{2}\\([^\\n]+\\))"
        ).find(block) ?: return

        phase.put("start", toIso(dates.groupValues[1]))
        phase.put("end", toIso(dates.groupValues[2]))
    }

    private fun toIso(value: String): String {
        val m = Regex("(\\d{4}-\\d{2}-\\d{2})\\([^)]*\\)(\\d{2}:\\d{2})\\s+GMT([+-]\\d+)")
            .find(value) ?: return value
        return try {
            val offset = "%+03d:00".format(Locale.US, m.groupValues[3].toInt())
            OffsetDateTime.parse(m.groupValues[1] + "T" + m.groupValues[2] + ":00" + offset).toString()
        } catch (_: Exception) {
            value
        }
    }

    private fun putDateRange(phase: JSONObject?, startDate: String, endDate: String) {
        if (phase == null) return
        val start = if (startDate.contains("T")) startDate else mergeDate(phase.optString("start"), startDate, false)
        val end = if (endDate.contains("T")) endDate else mergeDate(phase.optString("end"), endDate, true)
        phase.put("start", start)
        phase.put("end", end)
    }

    private fun mergeDate(old: String, date: String, end: Boolean): String {
        val d = try { LocalDate.parse(date) } catch (_: Exception) { return date }
        if (old.isBlank() || old == "null") return d.toString() + if (end) "T23:59:59Z" else "T00:00:00Z"
        return try {
            val dt = OffsetDateTime.parse(old)
            d.toString() + "T" + dt.toLocalTime() + dt.offset
        } catch (_: Exception) {
            d.toString() + if (end) "T23:59:59Z" else "T00:00:00Z"
        }
    }

    private fun parseEnglishDate(value: String): LocalDate? {
        return try {
            LocalDate.parse(value, DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
        } catch (_: Exception) {
            try {
                LocalDate.parse(value + ", 2026", DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.US))
            } catch (_: Exception) {
                null
            }
        }
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
}
