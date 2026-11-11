package com.wyrix.budget.data

import com.wyrix.budget.data.export.CsvExporter
import com.wyrix.budget.data.export.XlsxWriter
import com.wyrix.budget.domain.calc.Report
import com.wyrix.budget.domain.calc.ReportTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class ExportersTest {
    private val report = Report(
        title = "Monthly summary", periodLabel = "This month", generatedOn = "2026-11-09", currencyCode = "INR",
        summary = listOf("Total income" to "₹50,000.00", "Note" to "Tom & Jerry <test>"),
        tables = listOf(
            ReportTable(
                "Expenses by category", listOf("Category", "Amount (INR)", "Share %"),
                listOf(listOf("Food, drinks", "1250.50", "62.5"), listOf("=HYPERLINK(\"x\")", "-3.00", "1.0"), listOf("Say \"hi\"", "0.00", "0.0")),
                setOf(1, 2),
            ),
        ),
    )

    @Test fun csvQuotesCommasAndQuotes() {
        val csv = CsvExporter.toCsv(report)
        assertTrue(csv.contains("\"Food, drinks\",1250.50,62.5"))
        assertTrue(csv.contains("\"Say \"\"hi\"\"\""))
    }

    @Test fun csvNeutralisesFormulaInjectionInTextButNotNumbers() {
        val csv = CsvExporter.toCsv(report)
        assertTrue(csv.contains("'=HYPERLINK"))           // text cell is defused
        assertTrue(csv.contains(",-3.00,"))                // a negative number stays a number
        assertEquals("'+cmd", CsvExporter.escape("+cmd", false))
        assertEquals("-3.00", CsvExporter.escape("-3.00", true))
    }

    @Test fun csvHasHeaderSummaryAndTableBlocks() {
        val lines = CsvExporter.toCsv(report).split("\r\n")
        assertEquals("Monthly summary", lines[0])
        assertTrue(lines.contains("Category,Amount (INR),Share %"))
        assertTrue(lines.any { it.startsWith("Total income,") })
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z -> generateSequence { z.nextEntry }.forEach { out[it.name] = z.readBytes().toString(Charsets.UTF_8) } }
        return out
    }

    private fun xlsx(): Map<String, String> = unzip(ByteArrayOutputStream().also { XlsxWriter.write(report, it) }.toByteArray())

    @Test fun xlsxContainsTheRequiredParts() {
        val parts = xlsx()
        listOf("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/_rels/workbook.xml.rels", "xl/styles.xml", "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml").forEach {
            assertTrue("missing $it", parts.containsKey(it))
        }
    }

    @Test fun everyXlsxPartIsWellFormedXml() {
        val builder = DocumentBuilderFactory.newInstance().newDocumentBuilder()
        xlsx().forEach { (name, xml) -> builder.parse(ByteArrayInputStream(xml.toByteArray())); assertTrue(name, xml.startsWith("<?xml")) }
    }

    @Test fun xlsxWritesNumbersAsNumbersAndTextAsText() {
        val sheet = xlsx().getValue("xl/worksheets/sheet2.xml")
        assertTrue(sheet.contains("<c r=\"B2\"><v>1250.50</v></c>"))
        assertTrue(sheet.contains("<is><t xml:space=\"preserve\">Food, drinks</t></is>"))
        assertTrue(sheet.contains("<c r=\"B3\"><v>-3.00</v></c>"))
    }

    @Test fun xlsxEscapesXmlSpecialCharacters() {
        val summary = xlsx().getValue("xl/worksheets/sheet1.xml")
        assertTrue(summary.contains("Tom &amp; Jerry &lt;test&gt;"))
        assertFalse(summary.contains("Tom & Jerry"))
    }

    @Test fun columnLettersFollowSpreadsheetRules() {
        assertEquals("A", XlsxWriter.col(0)); assertEquals("Z", XlsxWriter.col(25)); assertEquals("AA", XlsxWriter.col(26))
        assertEquals("AB", XlsxWriter.col(27)); assertEquals("ZZ", XlsxWriter.col(701)); assertEquals("AAA", XlsxWriter.col(702))
    }

    @Test fun xmlEscapingDropsIllegalControlCharacters() {
        assertEquals("ab", XlsxWriter.xml("a\u0001b"))
        assertEquals("a&amp;b", XlsxWriter.xml("a&b"))
    }
}
