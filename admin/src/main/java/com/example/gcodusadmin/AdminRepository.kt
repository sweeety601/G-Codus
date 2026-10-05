package com.example.gcodusadmin

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream

class AdminRepository(private val github: GitHubClient) {

    fun loadCharacters(game: GameMeta): MutableList<AdminCharacter> {
        val rows = XlsxCodec.read(github.getFile(game.seedPath).bytes)
        if (rows.isEmpty()) return mutableListOf()

        val header = rows.first()
        val normalized = header.map(::normalizeHeader)

        val idCol = find(normalized, listOf("id", "ид", "идентификатор"))
        val nameCol = find(normalized, listOf("имя", "имя персонажа", "название", "name", "character name"))
        val elementCol = find(normalized, listOf("стихия", "элемент", "element", "attribute"))
        val rarityCol = find(normalized, listOf("редкость", "rarity", "звезды", "звёзды", "stars"))

        if (idCol < 0 || nameCol < 0) {
            throw IllegalStateException(
                "Файл " + game.seedPath + " прочитан, но в нём не найдены колонки ID и «Имя персонажа». Заголовки: " +
                    header.joinToString(" | ")
            )
        }

        return rows.drop(1).mapNotNull { row ->
            val id = row.getOrNull(idCol)?.trim().orEmpty()
            val name = row.getOrNull(nameCol)?.trim().orEmpty()
            if (id.isBlank() || name.isBlank()) {
                null
            } else {
                AdminCharacter(
                    id = id,
                    name = name,
                    element = row.getOrNull(elementCol)?.trim().orEmpty(),
                    rarity = parseRarity(row.getOrNull(rarityCol).orEmpty())
                )
            }
        }.toMutableList()
    }

    fun saveCharacters(game: GameMeta, chars: List<AdminCharacter>) {
        val currentBytes = github.getFile(game.seedPath).bytes
        val rows = XlsxCodec.read(currentBytes)
        val header = if (rows.isNotEmpty()) rows.first() else mutableListOf("ID", "Имя персонажа", "Портрет", "Стихия", "Редкость")

        val idCol = ensureColumn(header, listOf("id", "ид", "идентификатор"), "ID")
        val nameCol = ensureColumn(header, listOf("имя", "имя персонажа", "название", "name", "character name"), "Имя персонажа")
        val elementCol = ensureColumn(header, listOf("стихия", "элемент", "element", "attribute"), "Стихия")
        val rarityCol = ensureColumn(header, listOf("редкость", "rarity", "звезды", "звёзды", "stars"), "Редкость")

        val out = mutableListOf<MutableList<String>>()
        out += header.toMutableList()

        chars.sortedWith(compareBy<AdminCharacter>({ game.idPrefix }, {
            it.id.substringAfter('.', "").toIntOrNull() ?: Int.MAX_VALUE
        })).forEach { c ->
            val row = MutableList(header.size) { "" }
            row[idCol] = c.id
            row[nameCol] = c.name
            row[elementCol] = c.element
            row[rarityCol] = c.rarity.toString() + "★"
            out += row
        }

        val currentSha = github.getFileSha(game.seedPath)
        github.putFile(
            game.seedPath,
            XlsxCodec.write(out),
            currentSha,
            "Admin: update " + game.name + " character database"
        )
        github.triggerDataSync("characters:" + game.key)
    }

    fun nextId(game: GameMeta, chars: List<AdminCharacter>): String {
        var max = 0
        chars.forEach {
            if (it.id.startsWith(game.idPrefix + ".")) {
                val n = it.id.substringAfter('.').toIntOrNull() ?: 0
                max = maxOf(max, n)
            }
        }
        return game.idPrefix + "." + (max + 1)
    }

    fun loadBanners(game: GameMeta, confirmed: Boolean): Pair<MutableList<BannerRow>, String?> {
        val path = "banners/" + game.bannerPrefix + "_" + if (confirmed) "confirmed" else "leaks" + ".xlsx"
        return try {
            val file = github.getFile(path)
            val rows = XlsxCodec.read(file.bytes)
            if (rows.isEmpty()) {
                Pair(mutableListOf(), file.sha)
            } else {
                val header = rows.first().map(::normalizeHeader)
                fun col(names: List<String>) = find(header, names)

                val phase = col(listOf("версия и фаза", "version and phase", "phase"))
                val start = col(listOf("дата начала", "start date", "start_date"))
                val end = col(listOf("дата окончания", "end date", "end_date"))
                val chars = col(listOf("персонажи в составе баннера", "персонажи", "characters"))
                val four = col(listOf("4* в баннере", "4★ в баннере", "4*", "four star", "four_star"))

                if (phase < 0 || start < 0 || end < 0) {
                    throw IllegalStateException(
                        "Файл " + path + " прочитан, но не найдены обязательные колонки графика. Заголовки: " +
                            rows.first().joinToString(" | ")
                    )
                }

                val list = rows.drop(1).mapNotNull { row ->
                    val p = row.getOrNull(phase).orEmpty().trim()
                    if (p.isBlank()) {
                        null
                    } else {
                        BannerRow(
                            p,
                            row.getOrNull(start).orEmpty().trim(),
                            row.getOrNull(end).orEmpty().trim(),
                            splitIds(row.getOrNull(chars).orEmpty()),
                            splitIds(row.getOrNull(four).orEmpty())
                        )
                    }
                }.toMutableList()

                Pair(list, file.sha)
            }
        } catch (e: Exception) {
            if (e.message?.contains("GitHub API HTTP 404") == true || e.message?.contains("RAW HTTP 404") == true) {
                Pair(mutableListOf(), null)
            } else {
                throw e
            }
        }
    }

    fun saveBanners(game: GameMeta, confirmed: Boolean, rows: List<BannerRow>) {
        val path = "banners/" + game.bannerPrefix + "_" + if (confirmed) "confirmed" else "leaks" + ".xlsx"
        val currentSha = github.getFileSha(path)
        val table = mutableListOf<MutableList<String>>()
        table += mutableListOf("Версия и фаза", "Дата начала", "Дата окончания", "Персонажи в составе баннера", "4* в баннере")
        rows.forEach { b ->
            table += mutableListOf(
                b.phase,
                b.startDate,
                b.endDate,
                b.characters.joinToString(", "),
                b.fourStars.joinToString(", ")
            )
        }
        github.putFile(
            path,
            XlsxCodec.write(table),
            currentSha,
            "Admin: update " + game.name + " " + if (confirmed) "confirmed banners" else "leaks"
        )
        github.triggerDataSync("banners:" + game.key)
    }

    private fun find(header: List<String>, names: List<String>): Int {
        val aliases = names.map(::normalizeHeader).toSet()
        header.forEachIndexed { index, value ->
            if (value in aliases) return index
        }
        return -1
    }

    private fun ensureColumn(header: MutableList<String>, aliases: List<String>, canonical: String): Int {
        val normalized = header.map(::normalizeHeader)
        val index = find(normalized, aliases)
        if (index >= 0) return index
        header += canonical
        return header.lastIndex
    }

    private fun normalizeHeader(value: String): String =
        value.trim()
            .removePrefix("\uFEFF")
            .replace("Ё", "Е")
            .lowercase()

    private fun parseRarity(value: String): Int =
        Regex("""\d+""").find(value)?.value?.toIntOrNull() ?: 5

    private fun splitIds(value: String): MutableList<String> =
        value.split(',').map { it.trim() }.filter { it.isNotBlank() }.distinct().toMutableList()

    fun loadAllCharacters(): List<AdminCharacter> =
        GameCatalog.games.flatMap { loadCharacters(it) }

    fun readPortrait(uri: Uri, resolver: android.content.ContentResolver): ByteArray {
        resolver.openInputStream(uri).use { input ->
            val original = BitmapFactory.decodeStream(input)
                ?: throw IllegalArgumentException("Не удалось прочитать изображение")
            return compressWebp(original)
        }
    }

    private fun compressWebp(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        val format = if (android.os.Build.VERSION.SDK_INT >= 30)
            Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        if (!bitmap.compress(format, 95, out)) {
            throw IllegalStateException("Не удалось преобразовать изображение в WebP")
        }
        bitmap.recycle()
        return out.toByteArray()
    }
}