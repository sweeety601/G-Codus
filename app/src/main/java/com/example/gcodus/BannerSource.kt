package com.example.gcodus

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object BannerSource {
    private val games = listOf(
        Triple("wuwa", "Wuthering Waves", "01_Wuthering_Waves"),
        Triple("genshin", "Genshin Impact", "02_Genshin_Impact"),
        Triple("starrail", "Honkai: Star Rail", "03_Honkai_Star_Rail"),
        Triple("endfield", "Arknights: Endfield", "04_Arknights_Endfield"),
        Triple("zzz", "Zenless Zone Zero", "05_Zenless_Zone_Zero")
    )

    fun fetchNormalized(context: Context): String {
        val now = Instant.now()
        val out = JSONObject()
            .put("version", 7)
            .put("generated_at", now.toString())
            .put("source", "G-Codus banner Excel tables (direct)")
        val resultGames = JSONArray()

        for ((gameId, gameName, base) in games) {
            val confirmed = readRows(base, true)
            val leaks = readRows(base, false)
            val merged = linkedMapOf<String, BannerRowData>()
            leaks.forEach { merged[it.phase.lowercase()] = it.copy(confirmed = false) }
            confirmed.forEach { merged[it.phase.lowercase()] = it.copy(confirmed = true) }

            val rows = merged.values.sortedBy { it.startInstant }
            val current = rows.filter { !it.startInstant.isAfter(now) && now.isBefore(it.endInstant) }
            val future = rows.filter { it.startInstant.isAfter(now) }
            val history = rows.filter { !it.endInstant.isAfter(now) }
                .sortedByDescending { it.startInstant }

            fun phaseJson(row: BannerRowData): JSONObject =
                JSONObject()
                    .put("phase", row.phase)
                    .put("version", row.phase)
                    .put("start", row.start)
                    .put("end", row.end)
                    .put("characters", JSONArray(row.characters))
                    .put("five_star", JSONArray(row.characters))
                    .put("four_star", JSONArray(row.fourStars))
                    .put("source_status", if (row.confirmed) "confirmed" else "unconfirmed")
                    .put("unconfirmed", !row.confirmed)

            resultGames.put(
                JSONObject()
                    .put("id", gameId)
                    .put("name", gameName)
                    .put("current", JSONArray().apply { current.forEach { put(phaseJson(it)) } })
                    .put("next", JSONArray().apply { future.take(1).forEach { put(phaseJson(it)) } })
                    .put("upcoming", JSONArray().apply { future.drop(1).forEach { put(phaseJson(it)) } })
                    .put("history", JSONArray().apply { history.forEach { put(phaseJson(it)) } })
            )
        }

        return out.put("games", resultGames).toString()
    }

    private fun readRows(base: String, confirmed: Boolean): List<BannerRowData> {
        val suffix = if (confirmed) "confirmed" else "leaks"
        val rows = RemoteXlsx.fetchRows(
            listOf(
                "banners/" + base + "_" + suffix + ".xlsx",
                "banners/" + base + "_" + suffix
            )
        )
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { it.trim().lowercase().replace("ё", "е") }

        fun col(vararg names: String): Int {
            val aliases = names.map { it.trim().lowercase().replace("ё", "е") }.toSet()
            return header.indexOfFirst { it in aliases }
        }

        val phaseCol = col("версия и фаза", "phase", "version and phase")
        val startCol = col("дата начала", "start date", "start_date")
        val endCol = col("дата окончания", "end date", "end_date")
        val charsCol = col("персонажи в составе баннера", "персонажи", "characters")
        val fourCol = col("4* в баннере", "4★ в баннере", "4*", "four star", "four_star")
        if (phaseCol < 0 || startCol < 0 || endCol < 0) return emptyList()

        return rows.drop(1).mapNotNull { row ->
            val phase = row.getOrNull(phaseCol).orEmpty().trim()
            val start = normalizeDate(row.getOrNull(startCol).orEmpty())
            val end = normalizeDate(row.getOrNull(endCol).orEmpty())
            if (phase.isBlank() || start.isBlank() || end.isBlank()) return@mapNotNull null

            BannerRowData(
                phase = phase,
                start = start,
                end = end,
                characters = splitIds(row.getOrNull(charsCol).orEmpty()),
                fourStars = splitIds(row.getOrNull(fourCol).orEmpty()).take(3),
                confirmed = confirmed
            )
        }
    }

    private fun splitIds(value: String): List<String> =
        value.split(',', ';', '\n').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    private fun normalizeDate(value: String): String {
        val v = value.trim()
        if (v.isBlank()) return ""
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "yyyy-MM-dd",
            "dd.MM.yyyy HH:mm:ss",
            "dd.MM.yyyy HH:mm",
            "dd.MM.yyyy",
            "dd/MM/yyyy HH:mm",
            "dd/MM/yyyy"
        )
        for (pattern in patterns) {
            try {
                val formatter = DateTimeFormatter.ofPattern(pattern)
                return when {
                    pattern.contains("'T'") ->
                        LocalDateTime.parse(v, formatter).toInstant(ZoneOffset.UTC).toString()
                    pattern.contains("HH") ->
                        LocalDateTime.parse(v, formatter).toInstant(ZoneOffset.UTC).toString()
                    else ->
                        LocalDate.parse(v, formatter).atStartOfDay().toInstant(ZoneOffset.UTC).toString()
                }
            } catch (_: Exception) { }
        }
        return try {
            val numeric = v.toDouble()
            LocalDateTime.of(1899, 12, 30, 0, 0)
                .plusSeconds((numeric * 86_400.0).toLong())
                .toInstant(ZoneOffset.UTC)
                .toString()
        } catch (_: Exception) {
            ""
        }
    }

    private data class BannerRowData(
        val phase: String,
        val start: String,
        val end: String,
        val characters: List<String>,
        val fourStars: List<String>,
        val confirmed: Boolean
    ) {
        val startInstant: Instant get() = Instant.parse(start)
        val endInstant: Instant get() = Instant.parse(end)
    }
}
