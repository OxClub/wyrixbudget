package com.wyrix.budget.domain.calc

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

enum class BudgetPeriodType(val label: String) {
    WEEKLY("Weekly"), MONTHLY("Monthly"), YEARLY("Yearly"), CUSTOM("Custom period")
}

enum class AlertLevel { OK, NOTICE, WARNING, EXCEEDED }

data class AllocationInput(val categoryId: Long, val name: String, val amountMinor: Long)
data class CategorySpend(val categoryId: Long, val name: String, val spentMinor: Long)

data class BudgetInput(
    val id: Long,
    val name: String,
    val totalMinor: Long,
    val start: LocalDate,
    val end: LocalDate,
    val thresholds: List<Int>,
    /** true = a "category budget": only spending in allocated categories counts. */
    val countOnlyAllocated: Boolean,
)

data class CategoryBudgetStatus(
    val categoryId: Long,
    val name: String,
    val allocatedMinor: Long,
    val spentMinor: Long,
    val remainingMinor: Long,
    val utilizationBps: Long,
    val level: AlertLevel,
)

data class BudgetStatus(
    val budget: BudgetInput,
    val spentMinor: Long,
    val remainingMinor: Long,
    val utilizationBps: Long,
    val daysRemaining: Long,
    val daysTotal: Long,
    val level: AlertLevel,
    val reachedThreshold: Int?,
    val categories: List<CategoryBudgetStatus>,
    /** Spending in categories without an allocation (only for whole-budget mode). */
    val unallocatedSpentMinor: Long,
    val allocatedTotalMinor: Long,
) {
    val isOverspent: Boolean get() = spentMinor > budget.totalMinor
}

object BudgetCalculator {
    val DEFAULT_THRESHOLDS = listOf(50, 75, 90, 100)

    /** Utilization = spent / budget x 100, kept as basis points (floor) to avoid floating point. */
    fun utilizationBps(spentMinor: Long, budgetMinor: Long): Long =
        if (budgetMinor <= 0) 0 else spentMinor.coerceAtLeast(0) * 10_000 / budgetMinor

    fun reachedThreshold(utilBps: Long, thresholds: List<Int>): Int? =
        thresholds.filter { utilBps >= it * 100L }.maxOrNull()

    fun level(utilBps: Long, thresholds: List<Int>): AlertLevel {
        if (utilBps >= 10_000) return AlertLevel.EXCEEDED
        val reached = reachedThreshold(utilBps, thresholds) ?: return AlertLevel.OK
        return if (reached >= 75) AlertLevel.WARNING else AlertLevel.NOTICE
    }

    fun parseThresholds(csv: String): List<Int> =
        csv.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..200 }.distinct().sorted()

    fun status(
        budget: BudgetInput,
        allocations: List<AllocationInput>,
        spend: List<CategorySpend>,
        today: LocalDate,
    ): BudgetStatus {
        val spendById = spend.associate { it.categoryId to it.spentMinor }
        val allocatedIds = allocations.map { it.categoryId }.toSet()
        val allocatedSpent = allocations.sumOf { spendById[it.categoryId] ?: 0L }
        val totalSpend = spend.sumOf { it.spentMinor }
        val spent = if (budget.countOnlyAllocated) allocatedSpent else totalSpend

        val cats = allocations.map { a ->
            val s = spendById[a.categoryId] ?: 0L
            val u = utilizationBps(s, a.amountMinor)
            CategoryBudgetStatus(a.categoryId, a.name, a.amountMinor, s, a.amountMinor - s, u, level(u, budget.thresholds))
        }
        val util = utilizationBps(spent, budget.totalMinor)
        val effectiveToday = if (today.isBefore(budget.start)) budget.start else today
        val daysRemaining = if (today.isAfter(budget.end)) 0 else ChronoUnit.DAYS.between(effectiveToday, budget.end) + 1
        val unallocated = if (budget.countOnlyAllocated) 0L
        else spend.filter { it.categoryId !in allocatedIds }.sumOf { it.spentMinor }

        return BudgetStatus(
            budget = budget,
            spentMinor = spent,
            remainingMinor = budget.totalMinor - spent,
            utilizationBps = util,
            daysRemaining = daysRemaining,
            daysTotal = ChronoUnit.DAYS.between(budget.start, budget.end) + 1,
            level = level(util, budget.thresholds),
            reachedThreshold = reachedThreshold(util, budget.thresholds),
            categories = cats,
            unallocatedSpentMinor = unallocated,
            allocatedTotalMinor = allocations.sumOf { it.amountMinor },
        )
    }

    /** Default start/end dates for a period type containing [anchor]. */
    fun periodFor(type: BudgetPeriodType, anchor: LocalDate): Pair<LocalDate, LocalDate> = when (type) {
        BudgetPeriodType.WEEKLY -> {
            val start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            start to start.plusDays(6)
        }
        BudgetPeriodType.MONTHLY -> anchor.withDayOfMonth(1) to anchor.with(TemporalAdjusters.lastDayOfMonth())
        BudgetPeriodType.YEARLY -> anchor.withDayOfYear(1) to anchor.with(TemporalAdjusters.lastDayOfYear())
        BudgetPeriodType.CUSTOM -> anchor to anchor.plusDays(29)
    }
}
