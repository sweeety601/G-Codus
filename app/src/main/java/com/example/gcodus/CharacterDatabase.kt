package com.example.gcodus

import android.content.Context

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

object CharacterDatabase {
    private val tables = listOf(
        Triple("01_Wuthering_Waves.xlsx", "1", "wuwa"),
        Triple("02_Genshin_Impact.xlsx", "2", "genshin"),
        Triple("03_Honkai_Star_Rail.xlsx", "3", "starrail"),
        Triple("04_Arknights_Endfield.xlsx", "4", "endfield"),
        Triple("05_Zenless_Zone_Zero.xlsx", "5", "zzz")
    )

    fun fetch(context: Context): List<OnlineCharacter> {
        val result = mutableListOf<OnlineCharacter>()
        var failedTables = 0

        for ((filename, prefix, gameId) in tables) {
            val rows = try {
                RemoteXlsx.fetchRows(listOf("library/seed/" + filename))
            } catch (_: Exception) {
                failedTables++
                continue
            }
            parseTable(rows, prefix, gameId, result)
        }

        // Never replace a complete live database with a partial download.
        if (failedTables > 0) return emptyList()

        return result.distinctBy { it.gameId + "|" + it.id }
    }

    fun sync(context: Context): Boolean = fetch(context).isNotEmpty()

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
        if (idCol < 0 || nameCol < 0) return

        for (row in rows.drop(1)) {
            val id = row.getOrNull(idCol)?.trim().orEmpty()
            val name = row.getOrNull(nameCol)?.trim().orEmpty()
            if (id.isBlank() || name.isBlank()) continue
            if (!Regex("^" + Regex.escape(prefix) + "\\.[0-9]+$").matches(id)) continue

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
