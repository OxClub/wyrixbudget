package com.wyrix.budget.core

import com.wyrix.budget.core.money.CurrencyConversion
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.money.MoneyParseResult
import com.wyrix.budget.core.money.MoneyParser
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.domain.calc.Consolidation
import com.wyrix.budget.domain.model.Account
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.ExchangeRate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class ConversionAndMoneyExtrasTest {
    @Test fun convertsBetweenCurrenciesWithDifferentMinorDigits() {
        assertEquals(832_500L, CurrencyConversion.convert(10_000, CurrencyInfo.USD, CurrencyInfo.INR, BigDecimal("83.25")))
        assertEquals(55_000L, CurrencyConversion.convert(1_000, CurrencyInfo.JPY, CurrencyInfo.INR, BigDecimal("0.55")))
        assertEquals(1_800L, CurrencyConversion.convert(100_000, CurrencyInfo.INR, CurrencyInfo.JPY, BigDecimal("1.8")))
    }

    @Test fun roundsHalfUpToTheTargetMinorUnit() {
        assertEquals(83L, CurrencyConversion.convert(1, CurrencyInfo.USD, CurrencyInfo.INR, BigDecimal("83.255"))) // 0.83255 -> 0.83
        assertEquals(84L, CurrencyConversion.convert(1, CurrencyInfo.USD, CurrencyInfo.INR, BigDecimal("83.5")))   // 0.835 -> 0.84
    }

    @Test fun parsesRatesStrictly() {
        assertEquals(BigDecimal("83.25"), CurrencyConversion.parseRate("83.25"))
        assertEquals(BigDecimal("1234.5"), CurrencyConversion.parseRate("1,234.5"))
        assertNull(CurrencyConversion.parseRate("0")); assertNull(CurrencyConversion.parseRate("-1"))
        assertNull(CurrencyConversion.parseRate("abc")); assertNull(CurrencyConversion.parseRate(null)); assertNull(CurrencyConversion.parseRate(""))
    }

    private fun acct(id: Long, code: String, balance: Long) = AccountWithBalance(
        Account(id, "A$id", AccountType.BANK, code, 0, "", 0, 0), balance,
    )

    @Test fun consolidationNeverGuessesMissingRates() {
        val rates = mapOf("USD" to ExchangeRate("USD", "80", 5L, "manual"))
        val c = Consolidation.balance(listOf(acct(1, "INR", 100_000), acct(2, "USD", 1_000), acct(3, "EUR", 5_000)), CurrencyInfo.INR, rates)
        assertEquals(180_000L, c.totalMinor)               // 1,000 + 10 USD x 80 = 1,800
        assertEquals(listOf("A3"), c.excludedAccounts)      // EUR has no rate: left out, not guessed
        assertEquals(5L, c.oldestRateAsOf)
    }

    @Test fun formatsOtherCurrencies() {
        assertEquals("$12,345.67", MoneyFormatter.format(1_234_567, CurrencyInfo.USD))
        assertEquals("¥1,234,567", MoneyFormatter.format(1_234_567, CurrencyInfo.JPY))
        assertEquals("-€0.50", MoneyFormatter.format(-50, "EUR"))
        assertEquals("1500", MoneyFormatter.formatPlain(1_500, CurrencyInfo.JPY))
    }

    @Test fun zeroDecimalCurrenciesRejectFractions() {
        assertEquals(MoneyParseResult.Valid(1_500), MoneyParser.parse("1500", currency = CurrencyInfo.JPY))
        assertEquals(MoneyParseResult.Invalid, MoneyParser.parse("1500.5", currency = CurrencyInfo.JPY))
    }

    private val zone = ZoneId.of("Asia/Kolkata")
    private fun millis(y: Int, m: Int, d: Int) = ZonedDateTime.of(y, m, d, 0, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun financialYearStartsInAprilForIndia() {
        val nov = PeriodCalculator.financialYear(Instant.parse("2026-11-08T10:00:00Z"), zone, 4)
        assertEquals(millis(2026, 4, 1), nov.fromMillis); assertEquals(millis(2027, 4, 1), nov.toMillis)
        val feb = PeriodCalculator.financialYear(Instant.parse("2026-02-10T10:00:00Z"), zone, 4)
        assertEquals(millis(2025, 4, 1), feb.fromMillis)
        val last = PeriodCalculator.financialYear(Instant.parse("2026-11-08T10:00:00Z"), zone, 4, yearsBack = 1)
        assertEquals(millis(2025, 4, 1), last.fromMillis); assertEquals(millis(2026, 4, 1), last.toMillis)
        assertEquals(2026, PeriodCalculator.financialYearStart(Instant.parse("2026-11-08T10:00:00Z"), zone, 4))
        assertEquals(2025, PeriodCalculator.financialYearStart(Instant.parse("2026-03-31T10:00:00Z"), zone, 4))
    }

    @Test fun lastMonthAndPreviousWindow() {
        val r = PeriodCalculator.lastMonth(Instant.parse("2026-11-08T10:00:00Z"), zone)
        assertEquals(millis(2026, 10, 1), r.fromMillis); assertEquals(millis(2026, 11, 1), r.toMillis)
        val prev = PeriodCalculator.previous(r)
        assertEquals(r.fromMillis, prev.toMillis); assertEquals(r.toMillis - r.fromMillis, prev.toMillis - prev.fromMillis)
    }
}
