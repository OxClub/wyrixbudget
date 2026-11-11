package com.wyrix.budget.core

import com.wyrix.budget.core.money.MAX_AMOUNT_MINOR
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.money.MoneyParseResult
import com.wyrix.budget.core.money.MoneyParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    private fun valid(minor: Long) = MoneyParseResult.Valid(minor)

    @Test fun parsesWholeAndFractionalAmounts() {
        assertEquals(valid(123_456), MoneyParser.parse("1234.56"))
        assertEquals(valid(123_450), MoneyParser.parse("1,234.5"))
        assertEquals(valid(10_000), MoneyParser.parse("100"))
        assertEquals(valid(0), MoneyParser.parse("0"))
    }

    @Test fun parsesEdgeSpellings() {
        assertEquals(valid(50), MoneyParser.parse(".5"))
        assertEquals(valid(10_000), MoneyParser.parse("100."))
        assertEquals(valid(2_500), MoneyParser.parse("  25  "))
    }

    @Test fun avoidsFloatingPointErrors() {
        // 0.1 + 0.2 style values must be exact in minor units.
        assertEquals(valid(10), MoneyParser.parse("0.10"))
        assertEquals(valid(1_999), MoneyParser.parse("19.99"))
        assertEquals(valid(29), MoneyParser.parse("0.29")) // 0.29 * 100 = 28.999... in Double
    }

    @Test fun rejectsInvalidInput() {
        assertEquals(MoneyParseResult.Invalid, MoneyParser.parse("12.345"))
        assertEquals(MoneyParseResult.Invalid, MoneyParser.parse("abc"))
        assertEquals(MoneyParseResult.Invalid, MoneyParser.parse("1.2.3"))
        assertEquals(MoneyParseResult.Invalid, MoneyParser.parse("1e5"))
        assertEquals(MoneyParseResult.Invalid, MoneyParser.parse("-5"))
    }

    @Test fun emptyInputIsEmptyNotInvalid() {
        assertEquals(MoneyParseResult.Empty, MoneyParser.parse(""))
        assertEquals(MoneyParseResult.Empty, MoneyParser.parse("   "))
    }

    @Test fun negativeAmountsOnlyWhenAllowed() {
        assertEquals(valid(-500), MoneyParser.parse("-5", allowNegative = true))
        assertEquals(valid(-4_550), MoneyParser.parse("-45.5", allowNegative = true))
    }

    @Test fun rejectsAmountsAboveTheLimit() {
        assertEquals(valid(MAX_AMOUNT_MINOR), MoneyParser.parse("9999999999.99"))
        assertEquals(MoneyParseResult.Invalid, MoneyParser.parse("10000000000"))
        assertEquals(MoneyParseResult.Invalid, MoneyParser.parse("99999999999999999999"))
    }

    @Test fun parseOrNullReturnsNullForBadInput() {
        assertNull(MoneyParser.parseOrNull("nope"))
        assertEquals(500L, MoneyParser.parseOrNull("5"))
    }

    @Test fun formatsWithIndianGrouping() {
        assertEquals("₹0.00", MoneyFormatter.format(0))
        assertEquals("₹0.05", MoneyFormatter.format(5))
        assertEquals("₹999.99", MoneyFormatter.format(99_999))
        assertEquals("₹1,000.00", MoneyFormatter.format(100_000))
        assertEquals("₹1,234.56", MoneyFormatter.format(123_456))
        assertEquals("₹1,00,000.00", MoneyFormatter.format(10_000_000))
        assertEquals("₹12,34,56,789.00", MoneyFormatter.format(12_345_678_900))
    }

    @Test fun formatsNegativeAmounts() {
        assertEquals("-₹0.50", MoneyFormatter.format(-50))
        assertEquals("-₹1,234.56", MoneyFormatter.format(-123_456))
    }

    @Test fun formatsWithoutSymbol() {
        assertEquals("1,234.56", MoneyFormatter.format(123_456, showSymbol = false))
    }

    @Test fun plainFormatRoundTrips() {
        assertEquals("1250", MoneyFormatter.formatPlain(125_000))
        assertEquals("1250.50", MoneyFormatter.formatPlain(125_050))
        assertEquals("0.05", MoneyFormatter.formatPlain(5))
        listOf(0L, 5L, 99L, 100L, 123_456L, 999_999_999_999L).forEach { minor ->
            assertEquals(valid(minor), MoneyParser.parse(MoneyFormatter.formatPlain(minor)))
        }
    }
}
