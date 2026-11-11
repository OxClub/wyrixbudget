package com.wyrix.budget.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LoanAndSavingsMathTest {
    private fun rs(x: Long) = x * 100

    @Test fun emiMatchesTheStandardFormula() {
        // 1,00,000 at 12% p.a. over 12 months -> 8,884.88
        assertEquals(888_488L, LoanMath.emi(rs(100_000), 1200, 12))
    }

    @Test fun zeroInterestLoanIsSplitEvenly() {
        assertEquals(100_000L, LoanMath.emi(1_200_000, 0, 12))
    }

    @Test fun scheduleClearsTheLoanExactly() {
        val s = LoanMath.schedule(rs(500_000), 950, 60)
        assertEquals(60, s.schedule.size)
        assertEquals(0L, s.schedule.last().balanceMinor)
        assertEquals(rs(500_000), s.schedule.sumOf { it.principalMinor })
        assertEquals(s.totalPaymentMinor - rs(500_000), s.totalInterestMinor)
        assertTrue(s.schedule.zipWithNext().all { (a, b) -> b.interestMinor <= a.interestMinor })
    }

    @Test fun firstMonthInterestIsBalanceTimesMonthlyRate() {
        val s = LoanMath.schedule(rs(100_000), 1200, 12)
        assertEquals(rs(1_000), s.schedule.first().interestMinor) // 1% of 1,00,000
    }

    @Test fun paymentThatDoesNotCoverInterestNeverClears() {
        val interest = LoanMath.monthlyInterest(10_000_000, 1200)
        assertNull(LoanMath.amortize(10_000_000, 1200, interest))
        assertNotNull(LoanMath.amortize(10_000_000, 1200, interest + 1_000_000))
    }

    @Test fun compoundInterestForWholeYears() {
        assertEquals(11_000_000L, CompoundMath.futureValue(10_000_000, 1000, 1, 12))
        assertEquals(12_100_000L, CompoundMath.futureValue(10_000_000, 1000, 1, 24))
    }

    @Test fun savingsPlanMatchesThePromptExample() {
        val plan = SavingsMath.plan(rs(60_000), rs(12_000), LocalDate.of(2026, 1, 1), LocalDate.of(2027, 1, 1))!!
        assertEquals(rs(48_000), plan.remainingMinor)
        assertEquals(12, plan.months)
        assertEquals(rs(4_000), plan.monthlyMinor)
        assertEquals(13_151L, plan.dailyMinor) // ceil(48,000 / 365) in paise
    }

    @Test fun savingsPlanRejectsPastTargetDates() {
        assertNull(SavingsMath.plan(100, 0, LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 1)))
    }

    @Test fun savedAboveTargetNeedsNothingMore() {
        val plan = SavingsMath.plan(rs(1_000), rs(2_000), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 1))!!
        assertEquals(0L, plan.monthlyMinor)
    }

    @Test fun goalProgressMatchesThePromptExample() {
        // 35,000 of 90,000 = 38.88...% (floored to basis points)
        assertEquals(3888L, SavingsMath.progressBps(rs(35_000), rs(90_000)))
    }

    @Test fun emergencyFund() {
        assertEquals(rs(180_000), EmergencyFundMath.target(rs(30_000), 6))
        assertEquals(rs(130_000), EmergencyFundMath.shortfall(rs(180_000), rs(50_000)))
        assertEquals(0L, EmergencyFundMath.shortfall(rs(10), rs(50)))
    }

    private val debts = listOf(
        DebtPayoff.DebtInput("Card", rs(10_000), 3600, rs(500)),
        DebtPayoff.DebtInput("Loan", rs(5_000), 1200, rs(250)),
    )

    @Test fun avalancheNeverPaysMoreInterestThanSnowball() {
        val snow = DebtPayoff.simulate(debts, rs(1_000), DebtStrategy.SNOWBALL)
        val aval = DebtPayoff.simulate(debts, rs(1_000), DebtStrategy.AVALANCHE)
        assertTrue(snow.completed && aval.completed)
        assertTrue(aval.totalInterestMinor <= snow.totalInterestMinor)
        assertEquals("Loan", snow.payoffOrder.first())  // smallest balance first
        assertEquals("Card", aval.payoffOrder.first())  // highest rate first
    }

    @Test fun extraPaymentShortensPayoff() {
        val base = DebtPayoff.simulate(debts, 0, DebtStrategy.AVALANCHE)
        val extra = DebtPayoff.simulate(debts, rs(1_000), DebtStrategy.AVALANCHE)
        assertTrue(extra.months < base.months)
        assertTrue(extra.totalInterestMinor < base.totalInterestMinor)
    }

    @Test fun unpayableDebtIsReportedAsIncomplete() {
        val hopeless = listOf(DebtPayoff.DebtInput("Card", 10_000_000, 3600, 100_000))
        assertFalse(DebtPayoff.simulate(hopeless, 0, DebtStrategy.AVALANCHE).completed)
    }

    @Test fun affordability() {
        val r = AffordabilityMath.evaluate(rs(20_000), rs(15_000), rs(50_000), rs(10_000))
        assertFalse(r.withinBudget)
        assertEquals(-rs(5_000), r.remainingBudgetAfterMinor)
        assertTrue(r.balanceStaysPositive)
        assertEquals(rs(20_000), r.balanceAfterMinor)
    }

    @Test fun allocationAlwaysSumsToTheTotal() {
        val parts = BudgetAllocator.allocate(1_000, listOf(1, 1, 1))
        assertEquals(listOf(334L, 333L, 333L), parts)
        val weighted = BudgetAllocator.allocate(rs(30_000), listOf(20, 10, 13, 7, 17, 17, 16))
        assertEquals(rs(30_000), weighted.sum())
        assertEquals(listOf(0L, 0L), BudgetAllocator.allocate(0, listOf(1, 2)))
    }
}
