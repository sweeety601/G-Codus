package com.example.gcodusadmin

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory

object XlsxCodec {
    fun read(bytes: ByteArray): MutableList<MutableList<String>> {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheet = entries["xl/worksheets/sheet1.xml"]
            ?: throw IllegalStateException("Не найден первый лист Excel")
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
                    "inlineStr" -> childText(cell, "t").orEmpty()
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

    fun write(rows: List<List<String>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            put(zip, "[Content_Types].xml", contentTypes())
            put(zip, "_rels/.rels", rootRels())
            put(zip, "docProps/core.xml", coreProps())
            put(zip, "docProps/app.xml", appProps())
            put(zip, "xl/workbook.xml", workbook())
            put(zip, "xl/_rels/workbook.xml.rels", workbookRels())
            put(zip, "xl/styles.xml", styles())
            put(zip, "xl/worksheets/sheet1.xml", sheet(rows))
        }
        return out.toByteArray()
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
            val texts = si.getElementsByTagName("t")
            val b = StringBuilder()
            for (j in 0 until texts.length) b.append(texts.item(j).textContent)
            result += b.toString()
        }
        return result
    }

    private fun childText(node: org.w3c.dom.Node, name: String): String? {
        val children = node.childNodes
        for (i in 0 until children.length) {
            if (children.item(i).nodeName == name) return children.item(i).textContent
        }
        return null
    }

    private fun columnIndex(ref: String): Int {
        val letters = ref.takeWhile { it.isLetter() }
        var value = 0
        for (c in letters) value = value * 26 + (c.uppercaseChar() - 'A' + 1)
        return value - 1
    }

    private fun xml(value: String): String =
        value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

    private fun cell(col: Int, row: Int, value: String): String {
        val ref = colName(col) + row
        return "<c r=\"" + ref + "\" t=\"inlineStr\"><is><t xml:space=\"preserve\">" +
            xml(value) + "</t></is></c>"
    }

    private fun sheet(rows: List<List<String>>): String {
        val b = StringBuilder()
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        b.append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
        rows.forEachIndexed { rowIndex, row ->
            val rowNumber = rowIndex + 1
            b.append("<row r=\"").append(rowNumber).append("\">")
            row.forEachIndexed { colIndex, value ->
                if (value.isNotEmpty()) b.append(cell(colIndex, rowNumber, value))
            }
            b.append("</row>")
        }
        b.append("</sheetData></worksheet>")
        return b.toString()
    }

    private fun colName(index: Int): String {
        var n = index + 1
        val b = StringBuilder()
        while (n > 0) {
            val remainder = (n - 1) % 26
            b.append(('A'.code + remainder).toChar())
            n = (n - 1) / 26
        }
        return b.reverse().toString()
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
        "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
        "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
        "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
        "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
        "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>" +
        "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>" +
        "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>" +
        "</Types>"

    private fun rootRels() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>" +
        "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>" +
        "</Relationships>"

    private fun workbook() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">" +
        "<sheets><sheet name=\"Data\" sheetId=\"1\" r:id=\"rId1\"/></sheets></workbook>"

    private fun workbookRels() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
        "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
        "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>" +
        "</Relationships>"

    private fun styles() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
        "<fonts count=\"1\"><font><sz val=\"11\"/><name val=\"Aptos\"/></font></fonts>" +
        "<fills count=\"2\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill></fills>" +
        "<borders count=\"1\"><border/></borders>" +
        "<cellStyleXfs count=\"1\"><xf/></cellStyleXfs>" +
        "<cellXfs count=\"1\"><xf xfId=\"0\"/></cellXfs>" +
        "</styleSheet>"

    private fun coreProps() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\">" +
        "<dc:title>G-Codus Admin data</dc:title></cp:coreProperties>"

    private fun appProps() =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\" xmlns:vt=\"http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes\">" +
        "<Application>G-Codus Admin</Application></Properties>"
}