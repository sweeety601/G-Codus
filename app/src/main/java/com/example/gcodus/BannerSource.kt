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

    /*
     * Excel is the runtime source of truth.
     *
     * IMPORTANT:
     * - confirmed.xlsx is never merged with leaks.xlsx.
     * - current / next / history come ONLY from confirmed.xlsx.
     * - leaks.xlsx is used ONLY for the separate upcoming section.
     * - We never silently replace one phase with another file's row.
     */
    fun fetchNormalized(context: Context): String {
        val now = Instant.now()
        val out = JSONObject()
            .put("version", 8)
            .put("generated_at", now.toString())
            .put("source", "G-Codus banner Excel tables (direct)")
        val resultGames = JSONArray()

        for ((gameId, gameName, base) in games) {
            val confirmed = readRows(base, true)
                ?: throw IllegalStateException("Не удалось скачать подтверждённую таблицу: $base")
            val leaks = readRows(base, false) ?: emptyList()

            val confirmedSorted = confirmed.sortedBy { it.startInstant }
            val confirmedCurrent = confirmedSorted.filter {
                !it.startInstant.isAfter(now) && now.isBefore(it.endInstant)
            }
            val confirmedFuture = confirmedSorted.filter { it.startInstant.isAfter(now) }
            val confirmedHistory = confirmedSorted
                .filter { !it.endInstant.isAfter(now) }
                .sortedByDescending { it.startInstant }

            // Confirmed upcoming phases first, then leak rows.
            // A leak never replaces a confirmed row and is never used for current/history.
            val confirmedFutureWithoutNext = confirmedFuture.drop(1)
            // A conflict is defined ONLY by version + phase.
            val confirmedVersionPhases = confirmedSorted
                .map { versionPhaseKey(it.phase) }
                .toSet()
            val leakUpcoming = leaks
                .filter { it.endInstant.isAfter(now) }
                .filter { versionPhaseKey(it.phase) !in confirmedVersionPhases }
                .sortedBy { it.startInstant }

            fun phaseJson(row: BannerRowData): JSONObject =
                JSONObject()
                    .put("phase", row.phase)
                    .put("version", row.phase)
                    .put("start", row.start)
                    .put("end", row.end)
                    .put("characters", JSONArray(row.fiveStars))
                    .put("five_star", JSONArray(row.fiveStars))
                    .put("four_star", JSONArray(row.fourStars))
                    .put("source_status", if (row.confirmed) "confirmed" else "unconfirmed")
                    .put("unconfirmed", !row.confirmed)

            resultGames.put(
                JSONObject()
                    .put("id", gameId)
                    .put("name", gameName)
                    .put("current", JSONArray().apply {
                        confirmedCurrent.forEach { put(phaseJson(it)) }
                    })
                    .put("next", JSONArray().apply {
                        // All future confirmed and unconfirmed phases belong to the
                        // single main "Следующие баннеры" section.
                        val futureRows = (confirmedFuture + leakUpcoming)
                            .sortedBy { it.startInstant }
                        futureRows.forEach { put(phaseJson(it)) }
                    })
                    .put("upcoming", JSONArray())
                    .put("history", JSONArray().apply {
                        confirmedHistory.forEach { put(phaseJson(it)) }
                    })
            )
        }

        return out.put("games", resultGames).toString()
    }

    private fun readRows(base: String, confirmed: Boolean): List<BannerRowData>? {
        val suffix = if (confirmed) "confirmed" else "leaks"
        val rows = try {
            RemoteXlsx.fetchRows(
                listOf(
                    "banners/" + base + "_" + suffix + ".xlsx",
                    "banners/" + base + "_" + suffix
                )
            )
        } catch (_: Exception) {
            return null
        }
        if (rows.isEmpty()) return emptyList()

        val header = rows.first().map { normalizeHeader(it) }

        fun col(vararg names: String): Int {
            val aliases = names.map(::normalizeHeader).toSet()
            return header.indexOfFirst { it in aliases }
        }

        val phaseCol = col(
            "версия и фаза", "phase", "version and phase",
            "версия", "version"
        )
        val startCol = col(
            "дата начала", "start date", "start_date",
            "начало", "start"
        )
        val endCol = col(
            "дата окончания", "end date", "end_date",
            "конец", "end"
        )

        // Support both the old generic "characters" column and explicit 5★/4★ columns.
        val fiveStarCol = col(
            "5* в баннере", "5★ в баннере", "5*",
            "5 star", "5-star", "five star", "five_star",
            "5star", "featured 5 star", "featured 5★",
            "персонажи 5*", "персонажи 5★"
        )
        val charsCol = col(
            "персонажи в составе баннера", "персонажи", "characters",
            "character", "featured characters"
        )
        val fourCol = col(
            "4* в баннере", "4★ в баннере", "4*",
            "4 star", "4-star", "four star", "four_star",
            "4star", "featured 4 star", "featured 4★",
            "персонажи 4*", "персонажи 4★"
        )

        if (phaseCol < 0 || startCol < 0 || endCol < 0) return emptyList()

        return rows.drop(1).mapNotNull { row ->
            val phase = row.getOrNull(phaseCol).orEmpty().trim()
            val start = normalizeDate(row.getOrNull(startCol).orEmpty())
            val end = normalizeDate(row.getOrNull(endCol).orEmpty())
            if (phase.isBlank() || start.isBlank() || end.isBlank()) return@mapNotNull null

            val genericCharacters = splitIds(row.getOrNull(charsCol).orEmpty())
            val fiveStars = if (fiveStarCol >= 0) {
                splitIds(row.getOrNull(fiveStarCol).orEmpty())
            } else {
                genericCharacters
            }
            val fourStars = splitIds(row.getOrNull(fourCol).orEmpty())

            BannerRowData(
                phase = phase,
                start = start,
                end = end,
                fiveStars = fiveStars,
                fourStars = fourStars,
                confirmed = confirmed
            )
        }
    }

    private fun versionPhaseKey(value: String): String {
        val normalized = value.trim()
            .lowercase()
            .replace("ё", "е")
            .replace(Regex("\\s+"), " ")
        val match = Regex("""^(\\d+\\.\\d+)\\s*(?:phase|фаза)\\s*(\\d+)""").find(normalized)
        return if (match != null) {
            "${match.groupValues[1]}:${match.groupValues[2]}"
        } else {
            normalized
        }
    }

    private fun normalizeHeader(value: String): String =
        value.trim()
            .lowercase()
            .replace("ё", "е")
            .replace("★", "*")
            .replace("\u00A0", " ")
            .replace(Regex("\\s+"), " ")

    private fun splitIds(value: String): List<String> =
        value.split(',', ';', '\n', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

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
        val fiveStars: List<String>,
        val fourStars: List<String>,
        val confirmed: Boolean
    ) {
        val startInstant: Instant get() = Instant.parse(start)
        val endInstant: Instant get() = Instant.parse(end)
    }
}
