package com.wyrix.budget.domain.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class RecurrenceAndBudgetTest {
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    // ---- recurrence ----------------------------------------------------------------------

    @Test fun monthlyAnchoredOnThe31stDoesNotDrift() {
        val anchor = d(2026, 1, 31)
        assertEquals(d(2026, 2, 28), Recurrence.occurrence(anchor, Frequency.MONTHLY, 1, 1))
        assertEquals(d(2026, 3, 31), Recurrence.occurrence(anchor, Frequency.MONTHLY, 1, 2))
        assertEquals(d(2026, 3, 31), Recurrence.nextAfter(anchor, Frequency.MONTHLY, 1, d(2026, 2, 28)))
    }

    @Test fun oneTimeItemsHaveNoNextOccurrence() {
        assertNull(Recurrence.nextAfter(d(2026, 5, 1), Frequency.ONCE, 1, d(2026, 5, 1)))
    }

    @Test fun otherFrequencies() {
        val a = d(2026, 1, 10)
        assertEquals(d(2026, 1, 17), Recurrence.nextAfter(a, Frequency.WEEKLY, 1, a))
        assertEquals(d(2026, 4, 10), Recurrence.nextAfter(a, Frequency.QUARTERLY, 1, a))
        assertEquals(d(2026, 7, 10), Recurrence.nextAfter(a, Frequency.HALF_YEARLY, 1, a))
        assertEquals(d(2027, 1, 10), Recurrence.nextAfter(a, Frequency.YEARLY, 1, a))
        assertEquals(d(2026, 1, 20), Recurrence.nextAfter(a, Frequency.CUSTOM, 10, a))
        assertEquals(d(2026, 1, 11), Recurrence.nextAfter(a, Frequency.DAILY, 1, a))
    }

    @Test fun betweenListsOccurrencesInsideTheWindow() {
        val list = Recurrence.between(d(2026, 1, 1), Frequency.WEEKLY, 1, d(2026, 1, 10), d(2026, 1, 31))
        assertEquals(listOf(d(2026, 1, 15), d(2026, 1, 22), d(2026, 1, 29)), list)
    }

    // ---- budgets -------------------------------------------------------------------------

    private fun rs(x: Long) = x * 100
    private val thresholds = BudgetCalculator.DEFAULT_THRESHOLDS

    @Test fun utilizationAndLevels() {
        assertEquals(8500L, BudgetCalculator.utilizationBps(rs(5_100), rs(6_000)))
        assertEquals(AlertLevel.OK, BudgetCalculator.level(4_999, thresholds))
        assertEquals(AlertLevel.NOTICE, BudgetCalculator.level(5_000, thresholds))
        assertEquals(AlertLevel.WARNING, BudgetCalculator.level(7_500, thresholds))
        assertEquals(AlertLevel.WARNING, BudgetCalculator.level(9_999, thresholds))
        assertEquals(AlertLevel.EXCEEDED, BudgetCalculator.level(10_000, thresholds))
        assertEquals(AlertLevel.OK, BudgetCalculator.level(9_000, listOf(95)))
    }

    @Test fun customThresholdsAreParsedAndSanitised() {
        assertEquals(listOf(40, 80, 100), BudgetCalculator.parseThresholds("80, 40,100,abc,80,0"))
    }

    private val budget = BudgetInput(1, "November", rs(30_000), d(2026, 11, 1), d(2026, 11, 30), thresholds, countOnlyAllocated = false)
    private val allocations = listOf(
        AllocationInput(1, "Food", rs(6_000)),
        AllocationInput(2, "Transport", rs(3_000)),
    )
    private val spend = listOf(
        CategorySpend(1, "Food", rs(5_100)),
        CategorySpend(2, "Transport", rs(3_300)),
        CategorySpend(9, "Gaming", rs(700)),
    )

    @Test fun statusMatchesThePromptFormulas() {
        val s = BudgetCalculator.status(budget, allocations, spend, d(2026, 11, 9))
        assertEquals(rs(9_100), s.spentMinor)                    // all eligible expenses
        assertEquals(rs(30_000) - rs(9_100), s.remainingMinor)    // remaining = budget - spent
        assertEquals(3_033L, s.utilizationBps)                    // 30.33%
        assertEquals(22L, s.daysRemaining)                        // 9 Nov .. 30 Nov inclusive
        assertEquals(30L, s.daysTotal)
        assertEquals(rs(700), s.unallocatedSpentMinor)
        assertEquals(AlertLevel.OK, s.level)
    }

    @Test fun categoryRowsShowOverspend() {
        val s = BudgetCalculator.status(budget, allocations, spend, d(2026, 11, 9))
        val transport = s.categories.first { it.name == "Transport" }
        assertEquals(AlertLevel.EXCEEDED, transport.level)
        assertEquals(-rs(300), transport.remainingMinor)
        assertEquals(AlertLevel.WARNING, s.categories.first { it.name == "Food" }.level)
    }

    @Test fun categoryBudgetCountsOnlyAllocatedCategories() {
        val s = BudgetCalculator.status(budget.copy(countOnlyAllocated = true), allocations, spend, d(2026, 11, 9))
        assertEquals(rs(8_400), s.spentMinor)
        assertEquals(0L, s.unallocatedSpentMinor)
    }

    @Test fun daysRemainingBeforeDuringAndAfterThePeriod() {
        assertEquals(30L, BudgetCalculator.status(budget, allocations, spend, d(2026, 10, 20)).daysRemaining)
        assertEquals(1L, BudgetCalculator.status(budget, allocations, spend, d(2026, 11, 30)).daysRemaining)
        assertEquals(0L, BudgetCalculator.status(budget, allocations, spend, d(2026, 12, 1)).daysRemaining)
    }

    @Test fun zeroBudgetDoesNotDivideByZero() {
        assertEquals(0L, BudgetCalculator.utilizationBps(100, 0))
    }

    @Test fun defaultPeriods() {
        val anchor = d(2026, 11, 9) // a Monday
        assertEquals(d(2026, 11, 1) to d(2026, 11, 30), BudgetCalculator.periodFor(BudgetPeriodType.MONTHLY, anchor))
        assertEquals(d(2026, 11, 9) to d(2026, 11, 15), BudgetCalculator.periodFor(BudgetPeriodType.WEEKLY, anchor))
        assertEquals(d(2026, 1, 1) to d(2026, 12, 31), BudgetCalculator.periodFor(BudgetPeriodType.YEARLY, anchor))
    }

    // ---- forecast ------------------------------------------------------------------------

    private val today = d(2026, 11, 9)

    @Test fun forecastAddsScheduledItemsInsideTheHorizon() {
        val items = listOf(
            ScheduledItem("Rent", -rs(30_000), d(2026, 11, 15), d(2026, 11, 15), Frequency.MONTHLY, source = "Bill"),
            ScheduledItem("Salary", rs(50_000), d(2026, 11, 30), d(2026, 11, 30), Frequency.MONTHLY, source = "Recurring"),
        )
        val f = ForecastEngine.forecast(today, 30, rs(10_000), items)
        assertEquals(2, f.events.size)                       // Dec 15 rent is beyond Dec 9
        assertEquals(rs(30_000), f.endBalanceMinor)          // 10,000 - 30,000 + 50,000
        assertEquals(-rs(20_000), f.lowestBalanceMinor)      // dips after rent, before salary
        assertEquals(d(2026, 11, 15), f.lowestDate)
        assertEquals(rs(50_000), f.inflowMinor)
        assertEquals(rs(30_000), f.outflowMinor)
        assertTrue(f.assumptions.isNotEmpty())
    }

    @Test fun overdueItemsLandTodayAndThenResume() {
        val bill = ScheduledItem("Internet", -rs(1_000), d(2026, 11, 1), d(2026, 11, 1), Frequency.MONTHLY, source = "Bill")
        val f = ForecastEngine.forecast(today, 30, rs(5_000), listOf(bill))
        assertEquals(listOf(today, d(2026, 12, 1)), f.events.map { it.date })
        assertTrue(f.events.first().label.contains("overdue"))
    }

    @Test fun endDateStopsAnItem() {
        val loan = ScheduledItem("EMI", -rs(5_000), d(2026, 11, 10), d(2026, 11, 10), Frequency.MONTHLY, endDate = d(2026, 12, 10), source = "Loan")
        val f = ForecastEngine.forecast(today, 180, 0, listOf(loan))
        assertEquals(listOf(d(2026, 11, 10), d(2026, 12, 10)), f.events.map { it.date })
    }

    @Test fun oneTimeItemsAppearOnce() {
        val once = ScheduledItem("Fee", -100, d(2026, 11, 12), d(2026, 11, 12), Frequency.ONCE, source = "Bill")
        assertEquals(1, ForecastEngine.forecast(today, 90, 0, listOf(once)).events.size)
    }

    @Test fun emptyForecastKeepsTheBalance() {
        val f = ForecastEngine.forecast(today, 7, 1234, emptyList())
        assertEquals(1234L, f.endBalanceMinor)
        assertNull(f.lowestDate)
    }
}
