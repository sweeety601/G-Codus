package com.example.gcodusadmin

import org.json.JSONArray
import org.json.JSONObject
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.time.LocalDate

class AdminRepository(private val github: GitHubClient) {

    fun loadCharacters(game: GameMeta): MutableList<AdminCharacter> {
        val rows = XlsxCodec.read(github.getFile(game.seedPath).bytes)
        if (rows.isEmpty()) return mutableListOf()
        val header = rows.first().map { it.trim().lowercase() }
        val idCol = find(header, listOf("id", "ид", "идентификатор"))
        val nameCol = find(header, listOf("имя", "name"))
        val elementCol = find(header, listOf("стихия", "элемент", "element"))
        val rarityCol = find(header, listOf("редкость", "rarity"))
        if (idCol < 0 || nameCol < 0) throw IllegalStateException("В " + game.seedPath + " не найдены колонки ID/Имя")
        return rows.drop(1).mapNotNull { row ->
            val id = row.getOrNull(idCol)?.trim().orEmpty()
            val name = row.getOrNull(nameCol)?.trim().orEmpty()
            if (id.isBlank() || name.isBlank()) null
            else AdminCharacter(id, name, row.getOrNull(elementCol)?.trim().orEmpty(),
                row.getOrNull(rarityCol)?.trim()?.toIntOrNull() ?: 5)
        }.toMutableList()
    }

    fun saveCharacters(game: GameMeta, chars: List<AdminCharacter>) {
        val currentBytes = github.getFile(game.seedPath).bytes
        val rows = XlsxCodec.read(currentBytes)
        val header = if (rows.isNotEmpty()) rows.first() else mutableListOf("ID","Имя","Стихия","Редкость")
        val normalized = header.map { it.trim().lowercase() }
        val idCol = ensureColumn(header, normalized, "ID")
        val nameCol = ensureColumn(header, normalized, "Имя")
        val elementCol = ensureColumn(header, normalized, "Стихия")
        val rarityCol = ensureColumn(header, normalized, "Редкость")
        val out = mutableListOf<MutableList<String>>()
        out += header.toMutableList()
        chars.sortedBy { it.id.substringAfter('.', "").toIntOrNull() ?: Int.MAX_VALUE }
            .forEach { c ->
                val row = MutableList(header.size) { "" }
                row[idCol] = c.id
                row[nameCol] = c.name
                row[elementCol] = c.element
                row[rarityCol] = c.rarity.toString()
                out += row
            }
        val currentSha = github.getFileSha(game.seedPath)
        github.putFile(game.seedPath, XlsxCodec.write(out), currentSha, "Admin: update " + game.name + " character database")
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
            if (rows.isEmpty()) Pair(mutableListOf(), null)
            else {
                val header = rows.first().map { it.trim().lowercase() }
                fun col(names: List<String>) = find(header, names)
                val phase = col(listOf("версия и фаза", "version and phase", "phase"))
                val start = col(listOf("дата начала", "start date", "start_date"))
                val end = col(listOf("дата окончания", "end date", "end_date"))
                val chars = col(listOf("персонажи в составе баннера", "персонажи", "characters"))
                val four = col(listOf("4* в баннере", "4★ в баннере", "4*","four star", "four_star"))
                val list = rows.drop(1).mapNotNull { row ->
                    val p = row.getOrNull(phase).orEmpty().trim()
                    if (p.isBlank()) null else BannerRow(
                        p, row.getOrNull(start).orEmpty().trim(), row.getOrNull(end).orEmpty().trim(),
                        splitIds(row.getOrNull(chars).orEmpty()),
                        splitIds(row.getOrNull(four).orEmpty())
                    )
                }.toMutableList()
                Pair(list, null)
            }
        } catch (e: Exception) {
            if (e.message?.contains("HTTP 404") == true) {
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
        table += mutableListOf("Версия и фаза","Дата начала","Дата окончания","Персонажи в составе баннера","4* в баннере")
        rows.forEach { b ->
            table += mutableListOf(b.phase, b.startDate, b.endDate, b.characters.joinToString(", "), b.fourStars.joinToString(", "))
        }
        github.putFile(path, XlsxCodec.write(table), currentSha, "Admin: update " + game.name + " " + if (confirmed) "confirmed banners" else "leaks")
    }

    private fun find(header: List<String>, names: List<String>): Int {
        names.forEach { n ->
            val idx = header.indexOf(n.lowercase())
            if (idx >= 0) return idx
        }
        return -1
    }

    private fun ensureColumn(header: MutableList<String>, normalized: List<String>, name: String): Int {
        val idx = normalized.indexOf(name.lowercase())
        if (idx >= 0) return idx
        header += name
        return header.lastIndex
    }

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
        if (!bitmap.compress(format, 95, out)) throw IllegalStateException("Не удалось преобразовать изображение в WebP")
        bitmap.recycle()
        return out.toByteArray()
    }
}