package com.example.gcodus

import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Excel is the only runtime data source.
 *
 * The app never writes an Excel copy, JSON snapshot, ETag cache, or other
 * intermediate database to disk. A table is fetched from GitHub, parsed in
 * memory, and immediately converted to rows.
 */
object RemoteXlsx {

    fun fetchRows(pathCandidates: List<String>): MutableList<MutableList<String>> {
        var lastError: Exception? = null
        repeat(3) { attempt ->
            for (path in pathCandidates) {
                try {
                    return readFromRemote(path)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (attempt < 2) {
                try { Thread.sleep(350L) } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        throw lastError ?: IllegalStateException("Не удалось прочитать Excel")
    }

    private fun readFromRemote(path: String): MutableList<MutableList<String>> {
        val encodedPath = path.split('/').joinToString("/") {
            java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

        // There is deliberately no local/intermediate cache here.
        // The current table is read from the repository on every request.
        val url = URL(
            "https://raw.githubusercontent.com/sweeety601/G-Codus/main/" +
                encodedPath +
                "?gcodus_refresh=" + System.currentTimeMillis()
        )
        val connection = url.openConnection() as HttpURLConnection

        return try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("Cache-Control", "no-store, max-age=0")
            connection.setRequestProperty("Pragma", "no-cache")
            connection.setRequestProperty("User-Agent", "G-Codus/1.0")

            when (connection.responseCode) {
                in 200..299 -> {
                    // The XLSX bytes exist only in memory for this read.
                    connection.inputStream.use { input ->
                        readZip(input)
                    }
                }
                else -> error("HTTP " + connection.responseCode + " for " + path)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readZip(input: java.io.InputStream): MutableList<MutableList<String>> {
        val entries = HashMap<String, ByteArray>()

        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }

        val sheet = findFirstWorksheet(entries)
            ?: throw IllegalStateException("В Excel не найден лист")
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])

        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(sheet))
        val rows = doc.getElementsByTagName("row")
        val result = mutableListOf<MutableList<String>>()

        for (i in 0 until rows.length) {
            val row = rows.item(i)
            var maxCol = -1
            val values = HashMap<Int, String>()
            val children = row.childNodes

            for (j in 0 until children.length) {
                val cell = children.item(j)
                if (cell.nodeName != "c") continue
                val ref = cell.attributes?.getNamedItem("r")?.nodeValue ?: continue
                val col = columnIndex(ref)
                maxCol = maxOf(maxCol, col)
                val type = cell.attributes?.getNamedItem("t")?.nodeValue
                val raw = childText(cell, "v").orEmpty()
                val value = when (type) {
                    "s" -> shared.getOrNull(raw.toIntOrNull() ?: -1) ?: raw
                    "inlineStr" -> descendantText(cell, "t").orEmpty()
                    "str" -> raw.ifBlank { descendantText(cell, "t").orEmpty() }
                    "b" -> if (raw == "1") "TRUE" else if (raw == "0") "FALSE" else raw
                    else -> raw
                }
                values[col] = value
            }

            if (maxCol >= 0) {
                val rowValues = MutableList(maxCol + 1) { "" }
                values.forEach { (col, value) -> rowValues[col] = value }
                result += rowValues
            }
        }
        return result
    }

    private fun findFirstWorksheet(entries: Map<String, ByteArray>): ByteArray? {
        val direct = entries["xl/worksheets/sheet1.xml"]
        if (direct != null) return direct

        val workbook = entries["xl/workbook.xml"] ?: return entries
            .filterKeys { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .toSortedMap()
            .values
            .firstOrNull()

        val doc = try {
            DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(workbook))
        } catch (_: Exception) {
            null
        } ?: return entries
            .filterKeys { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .toSortedMap()
            .values
            .firstOrNull()

        val sheets = doc.getElementsByTagName("sheet")
        if (sheets.length == 0) return entries
            .filterKeys { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .toSortedMap()
            .values
            .firstOrNull()

        val rels = entries["xl/_rels/workbook.xml.rels"]
        if (rels != null) {
            try {
                val relDoc = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(ByteArrayInputStream(rels))
                val relationships = relDoc.getElementsByTagName("Relationship")
                val firstSheet = sheets.item(0).attributes?.getNamedItem("r:id")?.nodeValue
                for (i in 0 until relationships.length) {
                    val rel = relationships.item(i)
                    if (rel.attributes?.getNamedItem("Id")?.nodeValue == firstSheet) {
                        val target = rel.attributes?.getNamedItem("Target")?.nodeValue ?: continue
                        val normalized = if (target.startsWith("/")) target.removePrefix("/")
                            else "xl/" + target.removePrefix("./")
                        entries[normalized]?.let { return it }
                        val fixed = normalized.replace("xl/xl/", "xl/")
                        entries[fixed]?.let { return it }
                    }
                }
            } catch (_: Exception) { }
        }

        return entries
            .filterKeys { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }
            .toSortedMap()
            .values
            .firstOrNull()
    }

    private fun parseSharedStrings(bytes: ByteArray?): List<String> {
        if (bytes == null) return emptyList()
        val doc = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val nodes = doc.getElementsByTagName("si")
        val result = ArrayList<String>(nodes.length)
        for (i in 0 until nodes.length) {
            val si = nodes.item(i)
            val texts = (si as org.w3c.dom.Element).getElementsByTagName("t")
            val b = StringBuilder()
            for (j in 0 until texts.length) b.append(texts.item(j).textContent)
            result += b.toString()
        }
        return result
    }

    private fun childText(node: Node, name: String): String? {
        val children = node.childNodes
        for (i in 0 until children.length) {
            if (children.item(i).nodeName == name) return children.item(i).textContent
        }
        return null
    }

    private fun descendantText(node: Node, name: String): String? {
        val element = node as? org.w3c.dom.Element ?: return null
        val nodes = element.getElementsByTagName(name)
        if (nodes.length == 0) return null
        return nodes.item(0).textContent
    }

    private fun columnIndex(ref: String): Int {
        val letters = ref.takeWhile { it.isLetter() }
        var value = 0
        for (c in letters) value = value * 26 + (c.uppercaseChar() - 'A' + 1)
        return value - 1
    }
}
