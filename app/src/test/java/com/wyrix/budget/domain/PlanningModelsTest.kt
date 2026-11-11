package com.wyrix.budget.domain

import com.wyrix.budget.domain.calc.Frequency
import com.wyrix.budget.domain.model.Bill
import com.wyrix.budget.domain.model.BillStatus
import com.wyrix.budget.domain.model.Subscription
import com.wyrix.budget.domain.model.SubscriptionStatus
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PlanningModelsTest {
    private val today = LocalDate.of(2026, 11, 9)
    private fun bill(due: LocalDate, active: Boolean = true) = Bill(1, "Rent", 1000, null, null, due, due, Frequency.MONTHLY, listOf(3), active)

    @Test fun billStatusFollowsTheDueDate() {
        assertEquals(BillStatus.OVERDUE, bill(LocalDate.of(2026, 11, 8)).status(today))
        assertEquals(BillStatus.DUE_SOON, bill(LocalDate.of(2026, 11, 9)).status(today))
        assertEquals(BillStatus.DUE_SOON, bill(LocalDate.of(2026, 11, 16)).status(today))
        assertEquals(BillStatus.UPCOMING, bill(LocalDate.of(2026, 11, 17)).status(today))
        assertEquals(BillStatus.PAID, bill(LocalDate.of(2026, 11, 1), active = false).status(today))
    }

    private fun sub(cycle: Frequency, amount: Long) = Subscription(1, "S", amount, cycle, today, today, null, null, SubscriptionStatus.ACTIVE, emptyList())

    @Test fun subscriptionCostsAreNormalisedToMonthAndYear() {
        assertEquals(49_900L, sub(Frequency.MONTHLY, 49_900).monthlyCostMinor)
        assertEquals(598_800L, sub(Frequency.MONTHLY, 49_900).annualCostMinor)
        assertEquals(10_000L, sub(Frequency.QUARTERLY, 30_000).monthlyCostMinor)
        assertEquals(120_000L, sub(Frequency.QUARTERLY, 30_000).annualCostMinor)
        assertEquals(10_000L, sub(Frequency.YEARLY, 120_000).monthlyCostMinor)
        assertEquals(120_000L, sub(Frequency.YEARLY, 120_000).annualCostMinor)
        assertEquals(520_000L, sub(Frequency.WEEKLY, 120_000).monthlyCostMinor)
        assertEquals(6_240_000L, sub(Frequency.WEEKLY, 120_000).annualCostMinor)
    }
}
