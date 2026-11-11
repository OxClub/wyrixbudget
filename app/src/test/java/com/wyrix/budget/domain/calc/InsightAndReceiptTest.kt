package com.wyrix.budget.domain.calc

import com.wyrix.budget.core.money.CurrencyInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class InsightAndReceiptTest {
    private fun rs(x: Long) = x * 100

    private fun input(
        budgets: List<BudgetStatus> = emptyList(),
        income: Long = 0,
        expense: Long = 0,
        thisMonth: Map<String, Long> = emptyMap(),
        lastMonth: Map<String, Long> = emptyMap(),
        avg: Long = 0,
        due: Int = 0,
        goals: List<GoalProgress> = emptyList(),
    ) = InsightInput("2026-11", CurrencyInfo.INR, budgets, income, expense, thisMonth, lastMonth, avg, due, goals)

    private fun budgetWith(spentFood: Long): BudgetStatus {
        val b = BudgetInput(1, "Nov", rs(30_000), LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30), BudgetCalculator.DEFAULT_THRESHOLDS, false)
        return BudgetCalculator.status(b, listOf(AllocationInput(7, "Food", rs(6_000))), listOf(CategorySpend(7, "Food", spentFood)), LocalDate.of(2026, 11, 9))
    }

    @Test fun categoryBudgetNearLimitProducesAWarning() {
        val out = InsightEngine.generate(input(budgets = listOf(budgetWith(rs(5_100)))))
        assertTrue(out.any { it.text == "You have spent 85% of your Food budget." })
    }

    @Test fun exceededBudgetIsFlagged() {
        val out = InsightEngine.generate(input(budgets = listOf(budgetWith(rs(6_500)))))
        assertTrue(out.any { it.text == "You have exceeded your Food budget." })
    }

    @Test fun belowThresholdProducesNoBudgetInsight() {
        val out = InsightEngine.generate(input(budgets = listOf(budgetWith(rs(1_000)))))
        assertTrue(out.none { it.key.startsWith("budget") })
    }

    @Test fun categoryIncreaseNeedsBothAbsoluteAndPercentThreshold() {
        val big = InsightEngine.generate(input(thisMonth = mapOf("Shopping" to rs(5_200)), lastMonth = mapOf("Shopping" to rs(4_000))))
        assertTrue(big.any { it.text.startsWith("Your Shopping expenses increased by ₹1,200.00 (30%)") })
        val tiny = InsightEngine.generate(input(thisMonth = mapOf("Tea" to rs(110)), lastMonth = mapOf("Tea" to rs(100))))
        assertTrue(tiny.none { it.key.startsWith("increase") })
    }

    @Test fun incomeVersusExpenseRules() {
        assertTrue(InsightEngine.generate(input(income = rs(50_000), expense = rs(30_000))).any { it.key.startsWith("income-gt-expense") })
        assertTrue(InsightEngine.generate(input(income = rs(10_000), expense = rs(30_000))).any { it.key.startsWith("expense-gt-income") })
        assertTrue(InsightEngine.generate(input()).isEmpty())
    }

    @Test fun goalsDueDatesAndAverages() {
        val out = InsightEngine.generate(input(due = 3, avg = rs(450), goals = listOf(GoalProgress(4, "Laptop", 6_500))))
        assertTrue(out.any { it.text == "You have 3 upcoming payments in the next 7 days." })
        assertTrue(out.any { it.text == "Your savings goal \"Laptop\" is 65% complete." })
        assertTrue(out.any { it.text.contains("₹450.00") })
    }

    @Test fun keysAreUniqueSoDismissalWorks() {
        val out = InsightEngine.generate(input(budgets = listOf(budgetWith(rs(5_100))), income = 1, expense = 0, avg = 100, due = 1))
        assertEquals(out.size, out.map { it.key }.toSet().size)
    }

    // ---- receipt parser ------------------------------------------------------------------

    private val sample = """
        SUNRISE SUPERMARKET
        GSTIN 27ABCDE1234F1Z5
        Date: 08/11/2026
        Milk 2L 120.00
        Bread 45.00
        Subtotal 165.00
        CGST 4.13
        SGST 4.13
        Total 173.26
        Cash 200.00
    """.trimIndent()

    @Test fun extractsMerchantDateTotalAndTax() {
        val r = ReceiptTextParser.parse(sample)
        assertEquals("SUNRISE SUPERMARKET", r.merchant)
        assertEquals(LocalDate.of(2026, 11, 8), r.date)
        assertEquals(17_326L, r.totalMinor)
        assertEquals(826L, r.taxMinor)
    }

    @Test fun extractsLineItemsWithoutHeaderNoise() {
        val items = ReceiptTextParser.parse(sample).items
        assertEquals(listOf("Milk 2L" to 12_000L, "Bread" to 4_500L), items)
    }

    @Test fun fallsBackToTheLargestAmountWhenNoTotalLine() {
        val r = ReceiptTextParser.parse("Corner Cafe\nTea 20.00\nSamosa 35.50\nCake 120.00")
        assertEquals(12_000L, r.totalMinor)
        assertNull(r.taxMinor)
    }

    @Test fun handlesEmptyOrGarbageText() {
        val r = ReceiptTextParser.parse("")
        assertNull(r.merchant); assertNull(r.date); assertNull(r.totalMinor)
        assertNotNull(ReceiptTextParser.parse("???").items)
    }

    @Test fun parsesAlternativeDateFormats() {
        assertEquals(LocalDate.of(2026, 3, 5), ReceiptTextParser.parse("Shop\n05 Mar 2026\nTotal 10.00").date)
        assertEquals(LocalDate.of(2026, 3, 5), ReceiptTextParser.parse("Shop\n2026-03-05\nTotal 10.00").date)
    }
}
