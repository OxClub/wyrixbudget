package com.wyrix.budget.domain.calc

import com.wyrix.budget.core.money.MoneyParser
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Suggestions pulled out of OCR text. Always unverified: the user must review before saving. */
data class ParsedReceipt(
    val merchant: String?,
    val date: LocalDate?,
    val totalMinor: Long?,
    val taxMinor: Long?,
    val items: List<Pair<String, Long>>,
)

object ReceiptTextParser {
    private val moneyRegex = Regex("""(?<![\d.])(\d{1,3}(?:,\d{2,3})*(?:\.\d{1,2})|\d+\.\d{1,2}|\d{1,9})(?![\d])""")
    private val totalKeys = Regex("""(grand\s*total|net\s*(amount|total)|total\s*(amount|due|payable)?|amount\s*(due|payable)|balance\s*due)""", RegexOption.IGNORE_CASE)
    private val taxKeys = Regex("""\b(gst|cgst|sgst|igst|vat|tax)\b""", RegexOption.IGNORE_CASE)
    private val skipKeys = Regex("""(sub\s*total|subtotal|change|cash|tendered|discount|round|gstin|phone|\btel\b|invoice\s*no|bill\s*no|order\s*no)""", RegexOption.IGNORE_CASE)
    private val nonMerchant = Regex("""(tax invoice|invoice|receipt|bill|gstin|welcome|thank|cash memo|order)""", RegexOption.IGNORE_CASE)

    private val dateFormats = listOf(
        "dd/MM/yyyy", "dd-MM-yyyy", "dd.MM.yyyy", "d/M/yyyy", "d-M-yyyy",
        "yyyy-MM-dd", "dd MMM yyyy", "d MMM yyyy", "dd-MMM-yyyy", "MMM d, yyyy", "MMM dd, yyyy",
    ).map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }
    private val dateRegex = Regex("""(\d{1,2}[/.\-]\d{1,2}[/.\-]\d{4}|\d{4}-\d{2}-\d{2}|\d{1,2}[ \-][A-Za-z]{3}[ \-,]+\d{4}|[A-Za-z]{3} \d{1,2}, \d{4})""")

    fun parse(text: String): ParsedReceipt {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val merchant = lines.take(6).firstOrNull { l ->
            l.any { it.isLetter() } && !nonMerchant.containsMatchIn(l) && l.count { it.isDigit() } <= l.length / 3 && l.length in 3..40
        }

        val date = dateRegex.findAll(text).mapNotNull { m -> parseDate(m.value) }.firstOrNull()

        var total: Long? = null
        var tax = 0L
        var taxFound = false
        val items = ArrayList<Pair<String, Long>>()
        val allAmounts = ArrayList<Long>()

        for (line in lines) {
            val match = lastAmountMatch(line)
            val amount = match?.let { amountOf(it) }
            if (amount != null && !dateRegex.containsMatchIn(line) && !skipKeys.containsMatchIn(line)) allAmounts += amount
            when {
                amount == null || match == null -> Unit
                dateRegex.containsMatchIn(line) -> Unit
                taxKeys.containsMatchIn(line) -> { tax += amount; taxFound = true }
                skipKeys.containsMatchIn(line) -> Unit
                totalKeys.containsMatchIn(line) -> total = amount // later "total" lines win (grand total)
                else -> {
                    val name = line.removeRange(match.range).trim(' ', '-', ':', '*', '₹', '@')
                    if (name.any { it.isLetter() }) items += name to amount
                }
            }
        }
        if (total == null) total = allAmounts.maxOrNull()
        return ParsedReceipt(merchant, date, total, if (taxFound) tax else null, items.take(30))
    }

    private fun lastAmountMatch(line: String): MatchResult? = moneyRegex.findAll(line).lastOrNull()

    private fun amountOf(match: MatchResult): Long? {
        val raw = match.value
        if (!raw.contains('.') && raw.length > 7) return null
        return MoneyParser.parseOrNull(raw)?.takeIf { it > 0 }
    }

    private fun parseDate(raw: String): LocalDate? {
        val cleaned = raw.replace(Regex("""\s+"""), " ")
        for (f in dateFormats) {
            try { return LocalDate.parse(cleaned, f) } catch (_: Exception) { /* try next */ }
        }
        return null
    }
}
