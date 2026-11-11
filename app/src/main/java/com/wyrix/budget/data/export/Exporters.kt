package com.wyrix.budget.data.export

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import androidx.core.content.FileProvider
import com.wyrix.budget.domain.calc.Report
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object CsvExporter {
    fun toCsv(report: Report): String {
        val sb = StringBuilder()
        fun row(cells: List<String>, numeric: Set<Int> = emptySet()) {
            sb.append(cells.mapIndexed { i, c -> escape(c, i in numeric) }.joinToString(",")).append("\r\n")
        }
        row(listOf(report.title))
        row(listOf("Period", report.periodLabel))
        row(listOf("Generated", report.generatedOn))
        row(listOf("Currency", report.currencyCode))
        sb.append("\r\n")
        report.summary.forEach { row(listOf(it.first, it.second)) }
        for (t in report.tables) {
            sb.append("\r\n")
            row(listOf(t.title))
            row(t.headers)
            t.rows.forEach { row(it, t.numericColumns) }
        }
        return sb.toString()
    }

    /** Quotes as needed and neutralises spreadsheet formula injection ("=cmd...") in text cells. */
    internal fun escape(cell: String, numeric: Boolean): String {
        var v = cell
        if (!numeric && v.isNotEmpty() && v[0] in "=+-@\t\r") v = "'$v"
        return if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
    }
}

/** Minimal, dependency-free .xlsx writer (SpreadsheetML in a zip). Numeric columns become real numbers. */
object XlsxWriter {
    fun write(report: Report, out: OutputStream) {
        val sheets = ArrayList<Pair<String, String>>()
        val summaryRows = ArrayList<List<Cell>>()
        summaryRows += listOf(Cell.bold(report.title))
        summaryRows += listOf(Cell.text("Period"), Cell.text(report.periodLabel))
        summaryRows += listOf(Cell.text("Generated"), Cell.text(report.generatedOn))
        summaryRows += listOf(Cell.text("Currency"), Cell.text(report.currencyCode))
        summaryRows += emptyList<Cell>()
        report.summary.forEach { summaryRows += listOf(Cell.text(it.first), Cell.text(it.second)) }
        sheets += "Summary" to sheetXml(summaryRows)

        val used = mutableSetOf("Summary")
        for (t in report.tables) {
            var name = t.title.replace(Regex("""[\\/?*\[\]:]"""), " ").take(31).ifBlank { "Sheet" }
            var n = 2
            while (!used.add(name.lowercase())) name = name.take(28) + " " + n++
            val rows = ArrayList<List<Cell>>()
            rows += t.headers.map { Cell.bold(it) }
            t.rows.forEach { r -> rows += r.mapIndexed { i, v -> if (i in t.numericColumns && v.toBigDecimalOrNull() != null) Cell.number(v) else Cell.text(v) } }
            sheets += name to sheetXml(rows)
        }

        ZipOutputStream(out).use { zip ->
            fun entry(path: String, content: String) { zip.putNextEntry(ZipEntry(path)); zip.write(content.toByteArray(Charsets.UTF_8)); zip.closeEntry() }
            entry("[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>""" +
                    sheets.indices.joinToString("") { """<Override PartName="/xl/worksheets/sheet${it + 1}.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>""" } + "</Types>")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>""" +
                    sheets.mapIndexed { i, s -> """<sheet name="${xml(s.first)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>""" }.joinToString("") + "</sheets></workbook>")
            entry("xl/_rels/workbook.xml.rels",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">""" +
                    sheets.indices.joinToString("") { """<Relationship Id="rId${it + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet${it + 1}.xml"/>""" } +
                    """<Relationship Id="rId${sheets.size + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/></Relationships>""")
            entry("xl/styles.xml",
                """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts><fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills><borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs><cellXfs count="2"><xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/><xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/></cellXfs></styleSheet>""")
            sheets.forEachIndexed { i, s -> entry("xl/worksheets/sheet${i + 1}.xml", s.second) }
        }
    }

    private class Cell(val value: String, val kind: Char, val bold: Boolean) {
        companion object {
            fun text(v: String) = Cell(v, 's', false)
            fun bold(v: String) = Cell(v, 's', true)
            fun number(v: String) = Cell(v, 'n', false)
        }
    }

    private fun sheetXml(rows: List<List<Cell>>): String {
        val sb = StringBuilder("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
        rows.forEachIndexed { r, cells ->
            sb.append("<row r=\"${r + 1}\">")
            cells.forEachIndexed { c, cell ->
                val ref = col(c) + (r + 1)
                val style = if (cell.bold) " s=\"1\"" else ""
                if (cell.kind == 'n') sb.append("<c r=\"$ref\"$style><v>${xml(cell.value)}</v></c>")
                else sb.append("<c r=\"$ref\" t=\"inlineStr\"$style><is><t xml:space=\"preserve\">${xml(cell.value)}</t></is></c>")
            }
            sb.append("</row>")
        }
        return sb.append("</sheetData></worksheet>").toString()
    }

    internal fun col(index: Int): String {
        var i = index; var s = ""
        do { s = ('A' + i % 26) + s; i = i / 26 - 1 } while (i >= 0)
        return s
    }

    internal fun xml(s: String): String {
        val sb = StringBuilder()
        for (ch in s) when {
            ch == '&' -> sb.append("&amp;"); ch == '<' -> sb.append("&lt;"); ch == '>' -> sb.append("&gt;")
            ch == '"' -> sb.append("&quot;"); ch == '\'' -> sb.append("&apos;")
            ch.code < 0x20 && ch != '\n' && ch != '\r' && ch != '\t' -> Unit // illegal in XML 1.0
            else -> sb.append(ch)
        }
        return sb.toString()
    }
}

object PdfExporter {
    private const val W = 595; private const val H = 842; private const val M = 40f

    fun write(report: Report, out: OutputStream) {
        val doc = PdfDocument()
        val title = Paint().apply { textSize = 18f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val h2 = Paint().apply { textSize = 12f; typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val body = Paint().apply { textSize = 9f; color = Color.DKGRAY }
        val bold = Paint(body).apply { typeface = Typeface.DEFAULT_BOLD; color = Color.BLACK }
        val line = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
        val bar = Paint().apply { color = Color.rgb(14, 143, 106) }

        var pageNo = 0
        lateinit var page: PdfDocument.Page
        lateinit var canvas: Canvas
        var y = 0f
        fun newPage() {
            if (pageNo > 0) doc.finishPage(page)
            pageNo++
            page = doc.startPage(PdfDocument.PageInfo.Builder(W, H, pageNo).create())
            canvas = page.canvas
            y = M
            canvas.drawText("${report.title} - ${report.periodLabel}", M, H - 20f, body.apply { textSize = 8f })
            canvas.drawText("Page $pageNo", W - M - 40f, H - 20f, body)
            body.textSize = 9f
        }
        fun ensure(space: Float) { if (y + space > H - 40f) newPage() }

        newPage()
        canvas.drawText(report.title, M, y + 16f, title); y += 28f
        canvas.drawText("${report.periodLabel}   |   Generated ${report.generatedOn}   |   ${report.currencyCode}", M, y, body); y += 20f
        for ((k, v) in report.summary) {
            ensure(14f)
            canvas.drawText(k, M, y, bold); canvas.drawText(fit(v, body, W - 2 * M - 160f), M + 160f, y, body); y += 14f
        }
        y += 8f

        if (report.bars.isNotEmpty()) {
            ensure(30f)
            canvas.drawText("Overview", M, y, h2); y += 16f
            val max = report.bars.maxOf { kotlin.math.abs(it.second) }.coerceAtLeast(1)
            for ((label, v) in report.bars.take(12)) {
                ensure(14f)
                canvas.drawText(fit(label, body, 120f), M, y, body)
                val len = (kotlin.math.abs(v).toFloat() / max) * (W - 2 * M - 200f)
                canvas.drawRect(M + 125f, y - 8f, M + 125f + len.coerceAtLeast(1f), y + 1f, bar)
                canvas.drawText(java.math.BigDecimal.valueOf(v, 2).toPlainString(), M + 130f + len, y, body)
                y += 14f
            }
            y += 8f
        }

        for (t in report.tables) {
            ensure(40f)
            canvas.drawText(t.title, M, y, h2); y += 16f
            val cols = t.headers.size.coerceAtLeast(1)
            val widths = FloatArray(cols) { if (it == 0 || (cols > 5 && it == 2)) 1.6f else 1f }.let { w -> val total = w.sum(); FloatArray(cols) { (W - 2 * M) * w[it] / total } }
            fun drawRow(cells: List<String>, p: Paint) {
                var x = M
                for (i in 0 until cols) {
                    val s = fit(cells.getOrElse(i) { "" }, p, widths[i] - 4f)
                    if (i in t.numericColumns) canvas.drawText(s, x + widths[i] - 4f - p.measureText(s), y, p) else canvas.drawText(s, x, y, p)
                    x += widths[i]
                }
                y += 13f
                canvas.drawLine(M, y - 9f, W - M, y - 9f, line)
            }
            ensure(30f); drawRow(t.headers, bold)
            if (t.rows.isEmpty()) { canvas.drawText("No data for this period.", M, y, body); y += 14f }
            for (r in t.rows) { if (y + 13f > H - 40f) { newPage(); drawRow(t.headers, bold) }; drawRow(r, body) }
            y += 12f
        }
        doc.finishPage(page)
        doc.writeTo(out)
        doc.close()
    }

    private fun fit(text: String, paint: Paint, width: Float): String {
        if (paint.measureText(text) <= width) return text
        val n = paint.breakText(text, true, width - paint.measureText("..."), null)
        return text.take(n.coerceAtLeast(0)) + "..."
    }
}

/** Writes exports to the app cache and shares them through a FileProvider (no storage permission needed). */
object ExportShare {
    fun cacheFile(context: Context, name: String): File = File(File(context.cacheDir, "exports").apply { mkdirs() }, name)

    fun uriFor(context: Context, file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun shareIntent(context: Context, file: File, mime: String): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uriFor(context, file))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            },
            "Share ${file.name}",
        )

    fun print(context: Context, pdf: File, jobName: String) {
        val pm = context.getSystemService(Context.PRINT_SERVICE) as PrintManager
        pm.print(jobName, object : PrintDocumentAdapter() {
            override fun onLayout(old: PrintAttributes?, new: PrintAttributes?, cancel: CancellationSignal?, cb: LayoutResultCallback, extras: Bundle?) {
                if (cancel?.isCanceled == true) { cb.onLayoutCancelled(); return }
                cb.onLayoutFinished(PrintDocumentInfo.Builder(pdf.name).setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true)
            }
            override fun onWrite(pages: Array<out PageRange>?, dest: ParcelFileDescriptor, cancel: CancellationSignal?, cb: WriteResultCallback) {
                try {
                    FileInputStream(pdf).use { i -> FileOutputStream(dest.fileDescriptor).use { o -> i.copyTo(o) } }
                    cb.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (e: Exception) { cb.onWriteFailed(e.message) }
            }
        }, null)
    }
}
