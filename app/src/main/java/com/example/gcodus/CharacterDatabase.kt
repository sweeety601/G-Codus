package com.example.gcodus

import android.content.Context
import android.util.Log

data class OnlineCharacter(
    val gameId: String,
    val name: String,
    val slug: String,
    val announced: Boolean,
    val portraitUrl: String,
    val rarity: Int = 0,
    val id: String = "",
    val element: String = ""
)

data class CharacterFetchResult(
    val characters: List<OnlineCharacter>,
    val successfulGameIds: Set<String>,
    val failedGameIds: Set<String>
)

object CharacterDatabase {
    private val tables = listOf(
        Triple("01_Wuthering_Waves.xlsx", "1", "wuwa"),
        Triple("02_Genshin_Impact.xlsx", "2", "genshin"),
        Triple("03_Honkai_Star_Rail.xlsx", "3", "starrail"),
        Triple("04_Arknights_Endfield.xlsx", "4", "endfield"),
        Triple("05_Zenless_Zone_Zero.xlsx", "5", "zzz")
    )

    /**
     * Reads every character table directly from GitHub.
     *
     * A failure in one game's table must never block updates for the other
     * games. The caller can replace only the successfully fetched games and
     * keep the previous good data for a failed game.
     */
    fun fetchDetailed(context: Context): CharacterFetchResult {
        val result = mutableListOf<OnlineCharacter>()
        val successful = mutableSetOf<String>()
        val failed = mutableSetOf<String>()

        for ((filename, prefix, gameId) in tables) {
            try {
                val rows = RemoteXlsx.fetchRows(listOf("library/seed/" + filename))
                parseTable(rows, prefix, gameId, result)
                successful += gameId
            } catch (e: Exception) {
                failed += gameId
                Log.e("G-Codus", "Не удалось прочитать таблицу персонажей $filename", e)
            }
        }

        return CharacterFetchResult(
            characters = result.distinctBy { it.gameId + "|" + it.id },
            successfulGameIds = successful,
            failedGameIds = failed
        )
    }

    fun fetch(context: Context): List<OnlineCharacter> =
        fetchDetailed(context).characters

    fun sync(context: Context): Boolean =
        fetchDetailed(context).characters.isNotEmpty()

    private fun parseTable(
        rows: List<List<String>>,
        prefix: String,
        gameId: String,
        out: MutableList<OnlineCharacter>
    ) {
        if (rows.isEmpty()) return
        val header = rows.first().map {
            it.trim().removePrefix("﻿").replace("Ё", "Е").lowercase()
        }

        fun find(vararg names: String): Int {
            val aliases = names.map { it.trim().replace("Ё", "Е").lowercase() }.toSet()
            return header.indexOfFirst { it in aliases }
        }

        val idCol = find("id", "ид", "идентификатор")
        val nameCol = find("имя", "имя персонажа", "название", "name", "character name")
        val elementCol = find("стихия", "элемент", "element", "attribute")
        val rarityCol = find("редкость", "rarity", "звезды", "звёзды", "stars")
        if (idCol < 0 || nameCol < 0) {
            throw IllegalStateException("В таблице $gameId не найдены обязательные столбцы ID/имя")
        }

        for (row in rows.drop(1)) {
            val id = row.getOrNull(idCol)?.trim().orEmpty()
            val name = row.getOrNull(nameCol)?.trim().orEmpty()
            if (id.isBlank() || name.isBlank()) continue
            if (!Regex("^" + Regex.escape(prefix) + "\.[0-9]+$").matches(id)) continue

            val rarity = Regex("""\d+""")
                .find(row.getOrNull(rarityCol).orEmpty())
                ?.value
                ?.toIntOrNull() ?: 0

            out += OnlineCharacter(
                gameId = gameId,
                name = name,
                slug = id,
                announced = false,
                portraitUrl = imageUrl(id),
                rarity = rarity,
                id = id,
                element = row.getOrNull(elementCol).orEmpty()
            )
        }
    }

    fun imageUrl(id: String): String =
        "https://raw.githubusercontent.com/sweeety601/G-Codus/main/images/" +
            id + ".webp?v=" + System.currentTimeMillis()
}
