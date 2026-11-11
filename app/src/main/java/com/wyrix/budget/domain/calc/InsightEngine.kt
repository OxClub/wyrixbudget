package com.wyrix.budget.domain.calc

import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyFormatter

enum class InsightSeverity { INFO, POSITIVE, WARNING }

data class Insight(val key: String, val text: String, val severity: InsightSeverity)

data class GoalProgress(val id: Long, val name: String, val progressBps: Long)

data class InsightInput(
    val periodKey: String,                       // e.g. "2026-11"
    val currency: CurrencyInfo,
    val budgets: List<BudgetStatus>,             // active budgets
    val monthIncomeMinor: Long,
    val monthExpenseMinor: Long,
    val expenseByCategoryThisMonth: Map<String, Long>,
    val expenseByCategoryLastMonth: Map<String, Long>,
    val avgDailySpendMinor: Long,
    val paymentsDueThisWeek: Int,
    val goals: List<GoalProgress>,
)

/**
 * Deterministic, rule-based insights computed from recorded data only. Each insight has a stable key
 * so the user can dismiss it for that period. There are no predictions or personal advice.
 */
object InsightEngine {
    private const val MIN_INCREASE_MINOR = 50_000L // 500 in major units
    private const val MIN_INCREASE_PERCENT = 10L

    fun generate(i: InsightInput): List<Insight> {
        fun money(m: Long) = MoneyFormatter.format(m, i.currency)
        val out = ArrayList<Insight>()

        for (b in i.budgets) {
            for (c in b.categories) {
                val pct = c.utilizationBps / 100
                if (c.utilizationBps >= 10_000) {
                    out += Insight("budget-over:${b.budget.id}:${c.categoryId}:${i.periodKey}", "You have exceeded your ${c.name} budget.", InsightSeverity.WARNING)
                } else if (c.utilizationBps >= 8_500) {
                    out += Insight("budget-85:${b.budget.id}:${c.categoryId}:${i.periodKey}", "You have spent $pct% of your ${c.name} budget.", InsightSeverity.WARNING)
                }
            }
            if (b.utilizationBps >= 10_000) {
                out += Insight("budget-total-over:${b.budget.id}:${i.periodKey}", "You have exceeded your \"${b.budget.name}\" budget by ${money(-b.remainingMinor)}.", InsightSeverity.WARNING)
            } else if (b.utilizationBps >= 8_500) {
                out += Insight("budget-total-85:${b.budget.id}:${i.periodKey}", "You have used ${b.utilizationBps / 100}% of your \"${b.budget.name}\" budget.", InsightSeverity.WARNING)
            }
        }

        for ((name, now) in i.expenseByCategoryThisMonth) {
            val before = i.expenseByCategoryLastMonth[name] ?: continue
            val diff = now - before
            if (before > 0 && diff >= MIN_INCREASE_MINOR && diff * 100 / before >= MIN_INCREASE_PERCENT) {
                out += Insight(
                    "increase:$name:${i.periodKey}",
                    "Your $name expenses increased by ${money(diff)} (${diff * 100 / before}%) compared with last month.",
                    InsightSeverity.INFO,
                )
            }
        }

        if (i.paymentsDueThisWeek > 0) {
            val n = i.paymentsDueThisWeek
            out += Insight("due-week:${i.periodKey}:$n", "You have $n upcoming payment${if (n == 1) "" else "s"} in the next 7 days.", InsightSeverity.INFO)
        }

        if (i.monthIncomeMinor > 0 && i.monthIncomeMinor > i.monthExpenseMinor) {
            out += Insight("income-gt-expense:${i.periodKey}", "Your income this month is higher than your expenditure.", InsightSeverity.POSITIVE)
        } else if (i.monthExpenseMinor > i.monthIncomeMinor && i.monthExpenseMinor > 0) {
            out += Insight("expense-gt-income:${i.periodKey}", "Your expenditure this month is higher than your recorded income.", InsightSeverity.WARNING)
        }

        for (g in i.goals) {
            val pct = g.progressBps / 100
            if (pct in 10..99) out += Insight("goal:${g.id}:${pct / 25}", "Your savings goal \"${g.name}\" is $pct% complete.", InsightSeverity.POSITIVE)
            if (pct >= 100) out += Insight("goal-done:${g.id}", "You have reached your \"${g.name}\" savings goal.", InsightSeverity.POSITIVE)
        }

        if (i.avgDailySpendMinor > 0) {
            out += Insight("avg-daily:${i.periodKey}", "Your average daily spending this month is ${money(i.avgDailySpendMinor)}.", InsightSeverity.INFO)
        }
        return out
    }
}
