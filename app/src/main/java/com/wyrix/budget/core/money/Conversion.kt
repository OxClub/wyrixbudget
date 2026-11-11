package com.wyrix.budget.core.money

import java.math.BigDecimal
import java.math.RoundingMode

object CurrencyConversion {
    /** Rate semantics: 1 unit of [from] = [rate] units of [to]. Exact decimal arithmetic, rounded half-up. */
    fun convert(amountMinor: Long, from: CurrencyInfo, to: CurrencyInfo, rate: BigDecimal): Long =
        BigDecimal.valueOf(amountMinor, from.minorDigits)
            .multiply(rate)
            .setScale(to.minorDigits, RoundingMode.HALF_UP)
            .movePointRight(to.minorDigits)
            .toLong()

    fun parseRate(text: String?): BigDecimal? =
        text?.trim()?.replace(",", "")?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 && it < BigDecimal("1000000") }
}
