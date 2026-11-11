package com.wyrix.budget.core.money

import java.math.BigDecimal
import java.math.BigInteger

/** Money is always held as a Long count of minor units (paise, cents). Never Float/Double. */
data class CurrencyInfo(
    val code: String,
    val symbol: String,
    val minorDigits: Int,
    val indianGrouping: Boolean = false,
    val name: String = code,
) {
    companion object {
        val INR = CurrencyInfo("INR", "₹", 2, true, "Indian Rupee")
        val USD = CurrencyInfo("USD", "$", 2, false, "US Dollar")
        val EUR = CurrencyInfo("EUR", "€", 2, false, "Euro")
        val GBP = CurrencyInfo("GBP", "£", 2, false, "British Pound")
        val AED = CurrencyInfo("AED", "AED ", 2, false, "UAE Dirham")
        val JPY = CurrencyInfo("JPY", "¥", 0, false, "Japanese Yen")
        val CAD = CurrencyInfo("CAD", "CA$", 2, false, "Canadian Dollar")
        val AUD = CurrencyInfo("AUD", "A$", 2, false, "Australian Dollar")
        val SGD = CurrencyInfo("SGD", "S$", 2, false, "Singapore Dollar")

        val ALL: List<CurrencyInfo> = listOf(INR, USD, EUR, GBP, AED, JPY, CAD, AUD, SGD)
        fun byCode(code: String): CurrencyInfo = ALL.firstOrNull { it.code == code } ?: INR
    }
}

/** Largest single amount accepted. Keeps any realistic sum safely inside Long. */
const val MAX_AMOUNT_MINOR: Long = 999_999_999_999L

sealed interface MoneyParseResult {
    data class Valid(val minor: Long) : MoneyParseResult
    data object Empty : MoneyParseResult
    data object Invalid : MoneyParseResult
}

object MoneyParser {
    fun parse(
        input: String,
        allowNegative: Boolean = false,
        currency: CurrencyInfo = CurrencyInfo.INR,
    ): MoneyParseResult {
        val cleaned = input.trim().replace(",", "")
        if (cleaned.isEmpty() || cleaned == "-") return MoneyParseResult.Empty

        val negative = cleaned.startsWith("-")
        if (negative && !allowNegative) return MoneyParseResult.Invalid

        var body = if (negative) cleaned.substring(1) else cleaned
        val d = currency.minorDigits
        val pattern = if (d == 0) Regex("""^\d+$""") else Regex("""^(\d+(\.\d{0,$d})?|\.\d{1,$d})$""")
        if (!pattern.matches(body)) return MoneyParseResult.Invalid

        if (body.startsWith(".")) body = "0$body"
        body = body.removeSuffix(".")

        val minorBig: BigInteger = BigDecimal(body).movePointRight(d).toBigInteger()
        if (minorBig > BigInteger.valueOf(MAX_AMOUNT_MINOR)) return MoneyParseResult.Invalid

        val value = minorBig.toLong()
        return MoneyParseResult.Valid(if (negative) -value else value)
    }

    fun parseOrNull(
        input: String,
        allowNegative: Boolean = false,
        currency: CurrencyInfo = CurrencyInfo.INR,
    ): Long? = (parse(input, allowNegative, currency) as? MoneyParseResult.Valid)?.minor
}

object MoneyFormatter {
    /** INR uses Indian grouping (₹1,23,45,678.90); other currencies use groups of three. */
    fun format(minor: Long, currency: CurrencyInfo = CurrencyInfo.INR, showSymbol: Boolean = true): String {
        val bd = BigDecimal.valueOf(minor, currency.minorDigits)
        val plain = bd.abs().toPlainString()
        val intPart = plain.substringBefore('.')
        val frac = if (plain.contains('.')) plain.substringAfter('.') else ""
        val grouped = if (currency.indianGrouping) groupIndian(intPart) else groupWestern(intPart)
        val number = if (frac.isEmpty()) grouped else "$grouped.$frac"
        val sign = if (minor < 0) "-" else ""
        return sign + (if (showSymbol) currency.symbol else "") + number
    }

    fun format(minor: Long, currencyCode: String): String = format(minor, CurrencyInfo.byCode(currencyCode))

    fun formatPlain(minor: Long, currency: CurrencyInfo = CurrencyInfo.INR): String {
        val factor = BigDecimal.TEN.pow(currency.minorDigits).toLong()
        return if (minor % factor == 0L) (minor / factor).toString()
        else BigDecimal.valueOf(minor, currency.minorDigits).toPlainString()
    }

    internal fun groupWestern(digits: String): String =
        digits.reversed().chunked(3).joinToString(",").reversed()

    internal fun groupIndian(digits: String): String {
        if (digits.length <= 3) return digits
        val last3 = digits.takeLast(3)
        val rest = digits.dropLast(3)
        val parts = ArrayDeque<String>()
        var end = rest.length
        while (end > 0) {
            val start = maxOf(0, end - 2)
            parts.addFirst(rest.substring(start, end))
            end = start
        }
        return parts.joinToString(",") + "," + last3
    }
}
