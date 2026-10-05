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
                val e = zip.nextEntry ?: break
                entries[e.name] = zip.readBytes()
            }
        }
        val shared = parseSharedStrings(entries["xl/sharedStrings.xml"])
        val sheet = entries["xl/worksheets/sheet1.xml"]
            ?: throw IllegalStateException("Не найден первый лист Excel")
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = false
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(sheet))
        val rowNodes = doc.getElementsByTagName("row")
        val result = mutableListOf<MutableList<String>>()
        for (i in 0 until rowNodes.length) {
            val row = rowNodes.item(i)
            val cells = row.childNodes
            var maxCol = -1
            val values = HashMap<Int, String>()
            for (j in 0 until cells.length) {
                val node = cells.item(j)
                if (node.nodeName != "c") continue
                val ref = node.attributes?.getNamedItem("r")?.nodeValue ?: continue
                val col = columnIndex(ref)
                maxCol = maxOf(maxCol, col)
                val type = node.attributes?.getNamedItem("t")?.nodeValue
                val value = when (type) {
                    "s" -> {
                        val v = childText(node, "v") ?: ""
                        shared.getOrNull(v.toIntOrNull() ?: -1) ?: v
                    }
                    "inlineStr" -> childText(node, "t") ?: ""
                    else -> childText(node, "v") ?: ""
                }
                values[col] = value
            }
            val out = MutableList(maxCol + 1) { "" }
            values.forEach { (c, v) -> out[c] = v }
            result += out
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
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))
        val nodes = doc.getElementsByTagName("si")
        val out = ArrayList<String>(nodes.length)
        for (i in 0 until nodes.length) {
            val t = nodes.item(i).childNodes
            val b = StringBuilder()
            for (j in 0 until t.length) {
                val n = t.item(j)
                if (n.nodeName == "t") b.append(n.textContent)
                if (n.nodeName == "r") {
                    val r = n.childNodes
                    for (k in 0 until r.length) if (r.item(k).nodeName == "t") b.append(r.item(k).textContent)
                }
            }
            out += b.toString()
        }
        return out
    }

    private fun childText(node: org.w3c.dom.Node, name: String): String? {
        val children = node.childNodes
        for (i in 0 until children.length) if (children.item(i).nodeName == name) return children.item(i).textContent
        return null
    }

    private fun columnIndex(ref: String): Int {
        val letters = ref.takeWhile { it.isLetter() }
        var n = 0
        for (c in letters) n = n * 26 + (c.uppercaseChar() - 'A' + 1)
        return n - 1
    }

    private fun xml(s: String) = s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
        .replace(""","&quot;").replace("'","&apos;")

    private fun cell(col: Int, row: Int, value: String): String {
        val ref = colName(col) + row
        return "<c r="" + ref + "" t="inlineStr"><is><t xml:space="preserve">" +
            xml(value) + "</t></is></c>"
    }

    private fun sheet(rows: List<List<String>>): String {
        val b = StringBuilder()
        b.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { ri, row ->
            val rn = ri + 1
            b.append("<row r="" + rn + "">")
            row.forEachIndexed { ci, v -> b.append(cell(ci, rn, v)) }
            b.append("</row>")
        }
        b.append("</sheetData></worksheet>")
        return b.toString()
    }

    private fun colName(n: Int): String {
        var x = n + 1
        val b = StringBuilder()
        while (x > 0) {
            val r = (x - 1) % 26
            b.append(('A'.code + r).toChar())
            x = (x - 1) / 26
        }
        return b.reverse().toString()
    }

    private fun put(zip: ZipOutputStream, name: String, text: String) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/><Override PartName="/docProps/app.xml" ContentType="application/vnd.openxmlformats-officedocument.extended-properties+xml"/></Types>"""
    private fun rootRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties" Target="docProps/core.xml"/><Relationship Id="rId3" Type="http://schemas.openxmlformats.org/package/2006/relationships/extended-properties" Target="docProps/app.xml"/></Relationships>"""
    private fun workbook() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="rId1"/></sheets></workbook>"""
    private fun workbookRels() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>"""
    private fun styles() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="1"><font><sz val="11"/><name val="Aptos"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border/></borders><cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="1"><xf xfId="0"/></cellXfs></styleSheet>"""
    private fun coreProps() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>G-Codus Admin data</dc:title></cp:coreProperties>"""
    private fun appProps() = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><Application>G-Codus Admin</Application></Properties>"""
}