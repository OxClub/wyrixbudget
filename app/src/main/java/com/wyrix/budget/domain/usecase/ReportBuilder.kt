package com.wyrix.budget.domain.usecase

import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.time.TimeRange
import com.wyrix.budget.domain.calc.Report
import com.wyrix.budget.domain.calc.ReportTable
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.*
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class ReportType(val label: String) {
    MONTHLY_SUMMARY("Monthly summary"),
    ANNUAL_SUMMARY("Annual / financial-year summary"),
    CATEGORY("Category spending"),
    ACCOUNT_STATEMENT("Account statement"),
    BUDGET_PERFORMANCE("Budget performance"),
    SAVINGS_GOALS("Savings goals"),
    DEBT_SUMMARY("Debt and loan summary"),
    SUBSCRIPTIONS("Subscriptions"),
    TRANSACTIONS("Transaction list"),
}

/** Builds exporter-neutral reports from recorded data. Transfers and adjustments never count as income or expense. */
class ReportBuilder @Inject constructor(
    private val transactions: TransactionRepository,
    private val accounts: AccountRepository,
    private val budgets: BudgetRepository,
    private val goals: GoalRepository,
    private val loans: LoanRepository,
    private val subscriptions: SubscriptionRepository,
    private val analytics: AnalyticsRepository,
) {
    private fun plain(minor: Long, cur: CurrencyInfo): String = BigDecimal.valueOf(minor, cur.minorDigits).toPlainString()
    private fun pct(part: Long, whole: Long): String = if (whole <= 0) "0.0" else BigDecimal(part * 1000 / whole).movePointLeft(1).toPlainString()

    suspend fun build(
        type: ReportType,
        range: TimeRange,
        periodLabel: String,
        base: CurrencyInfo,
        zone: ZoneId,
        today: LocalDate,
        accountId: Long? = null,
    ): Report {
        fun day(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toString()
        val money = { m: Long -> MoneyFormatter.format(m, base) }
        val header = "Amount (${base.code})"
        val generated = today.toString()

        return when (type) {
            ReportType.MONTHLY_SUMMARY, ReportType.CATEGORY -> {
                val exp = analytics.byCategory(true, range)
                val inc = analytics.byCategory(false, range)
                val totalExp = exp.sumOf { it.amountMinor }
                val totalInc = inc.sumOf { it.amountMinor }
                val net = totalInc - totalExp
                Report(
                    type.label, periodLabel, generated, base.code,
                    summary = listOf(
                        "Total income" to money(totalInc), "Total expenses" to money(totalExp), "Net savings" to money(net),
                        "Savings rate" to (if (totalInc > 0) "${pct(net, totalInc)}%" else "n/a"),
                        "Note" to "Transfers and reconciliation adjustments are excluded.",
                    ),
                    tables = listOf(
                        ReportTable("Expenses by category", listOf("Category", header, "Share %"), exp.map { listOf(it.name, plain(it.amountMinor, base), pct(it.amountMinor, totalExp)) }, setOf(1, 2)),
                        ReportTable("Income by category", listOf("Category", header, "Share %"), inc.map { listOf(it.name, plain(it.amountMinor, base), pct(it.amountMinor, totalInc)) }, setOf(1, 2)),
                    ),
                    bars = exp.take(10).map { it.name to it.amountMinor },
                )
            }

            ReportType.ANNUAL_SUMMARY -> {
                val months = analytics.monthlyTotals(range, zone)
                val exp = analytics.byCategory(true, range)
                val totalInc = months.sumOf { it.incomeMinor }
                val totalExp = months.sumOf { it.expenseMinor }
                Report(
                    type.label, periodLabel, generated, base.code,
                    summary = listOf("Total income" to money(totalInc), "Total expenses" to money(totalExp), "Net savings" to money(totalInc - totalExp)),
                    tables = listOf(
                        ReportTable("Month by month", listOf("Month", "Income", "Expenses", "Net"), months.map { listOf(it.month, plain(it.incomeMinor, base), plain(it.expenseMinor, base), plain(it.incomeMinor - it.expenseMinor, base)) }, setOf(1, 2, 3)),
                        ReportTable("Expenses by category", listOf("Category", header, "Share %"), exp.map { listOf(it.name, plain(it.amountMinor, base), pct(it.amountMinor, totalExp)) }, setOf(1, 2)),
                    ),
                    bars = months.map { it.month to it.expenseMinor },
                )
            }

            ReportType.ACCOUNT_STATEMENT -> {
                val account = accounts.observeAccounts().first().firstOrNull { it.account.id == accountId }
                    ?: return Report(type.label, periodLabel, generated, base.code, listOf("Error" to "Choose an account."), emptyList())
                val cur = CurrencyInfo.byCode(account.account.currencyCode)
                val before = transactions.listInRange(TimeRange(0, range.fromMillis)).map { it.transaction }
                var running = account.account.openingBalanceMinor + before.sumOf { effect(it, account.account.id) }
                val opening = running
                val rows = transactions.listInRange(range).filter { it.transaction.accountId == account.account.id || it.transaction.transferAccountId == account.account.id }.map { item ->
                    val t = item.transaction
                    val e = effect(t, account.account.id)
                    running += e
                    val desc = if (t.type == TransactionType.TRANSFER) {
                        if (t.accountId == account.account.id) "Transfer to ${item.transferAccountName}" else "Transfer from ${item.accountName}"
                    } else t.title
                    listOf(day(t.occurredAt), desc, if (e < 0) plain(-e, cur) else "", if (e > 0) plain(e, cur) else "", plain(running, cur))
                }
                Report(
                    "Statement: ${account.account.name}", periodLabel, generated, cur.code,
                    summary = listOf("Opening balance" to MoneyFormatter.format(opening, cur), "Closing balance" to MoneyFormatter.format(running, cur)),
                    tables = listOf(ReportTable("Transactions", listOf("Date", "Description", "Debit (${cur.code})", "Credit (${cur.code})", "Balance (${cur.code})"), rows, setOf(2, 3, 4))),
                )
            }

            ReportType.BUDGET_PERFORMANCE -> {
                val from = Instant.ofEpochMilli(range.fromMillis).atZone(zone).toLocalDate()
                val to = Instant.ofEpochMilli(range.toMillis - 1).atZone(zone).toLocalDate()
                val list = budgets.observeStatuses(today, zone).first().filter { !it.budget.end.isBefore(from) && !it.budget.start.isAfter(to) }
                val overview = list.map { listOf(it.budget.name, "${it.budget.start} to ${it.budget.end}", plain(it.budget.totalMinor, base), plain(it.spentMinor, base), plain(it.remainingMinor, base), pct(it.spentMinor, it.budget.totalMinor)) }
                val lines = list.flatMap { b -> b.categories.map { listOf(b.budget.name, it.name, plain(it.allocatedMinor, base), plain(it.spentMinor, base), plain(it.remainingMinor, base), (it.utilizationBps / 100.0).let { v -> "%.1f".format(v) }) } }
                Report(
                    type.label, periodLabel, generated, base.code,
                    summary = listOf("Budgets in period" to list.size.toString(), "Over budget" to list.count { it.isOverspent }.toString()),
                    tables = listOf(
                        ReportTable("Budgets", listOf("Budget", "Period", "Budget", "Spent", "Remaining", "Used %"), overview, setOf(2, 3, 4, 5)),
                        ReportTable("Category performance", listOf("Budget", "Category", "Allocated", "Spent", "Remaining", "Used %"), lines, setOf(2, 3, 4, 5)),
                    ),
                    bars = list.map { it.budget.name to it.spentMinor },
                )
            }

            ReportType.SAVINGS_GOALS -> {
                val list = goals.observeGoals().first()
                Report(
                    type.label, periodLabel, generated, base.code,
                    summary = listOf("Goals" to list.size.toString(), "Total saved" to money(list.sumOf { it.savedMinor }), "Total target" to money(list.sumOf { it.goal.targetMinor })),
                    tables = listOf(ReportTable("Goals", listOf("Goal", "Status", "Target", "Saved", "Remaining", "Progress %", "Target date"),
                        list.map { listOf(it.goal.name, it.goal.status.label, plain(it.goal.targetMinor, base), plain(it.savedMinor, base), plain((it.goal.targetMinor - it.savedMinor).coerceAtLeast(0), base), pct(it.savedMinor, it.goal.targetMinor), it.goal.targetDate?.toString() ?: "") }, setOf(2, 3, 4, 5))),
                    bars = list.map { it.goal.name to it.savedMinor },
                )
            }

            ReportType.DEBT_SUMMARY -> {
                val list = loans.observeAll().first().filter { it.loan.isActive }
                Report(
                    type.label, periodLabel, generated, base.code,
                    summary = listOf("Active debts" to list.size.toString(), "Total outstanding" to money(list.sumOf { it.outstandingMinor }), "Total monthly payments" to money(list.sumOf { it.loan.emiMinor })),
                    tables = listOf(ReportTable("Debts", listOf("Lender", "Type", "Outstanding", "Interest %", "Payment", "Next payment"),
                        list.map { listOf(it.loan.lender, it.loan.type.label, plain(it.outstandingMinor, base), BigDecimal(it.loan.rateBps).movePointLeft(2).toPlainString(), plain(it.loan.emiMinor, base), it.loan.nextPaymentDate.toString()) }, setOf(2, 3, 4))),
                    bars = list.map { it.loan.lender to it.outstandingMinor },
                )
            }

            ReportType.SUBSCRIPTIONS -> {
                val list = subscriptions.observeAll().first()
                val active = list.filter { it.status == SubscriptionStatus.ACTIVE }
                Report(
                    type.label, periodLabel, generated, base.code,
                    summary = listOf("Active subscriptions" to active.size.toString(), "Monthly cost" to money(active.sumOf { it.monthlyCostMinor }), "Annual cost" to money(active.sumOf { it.annualCostMinor })),
                    tables = listOf(ReportTable("Subscriptions", listOf("Name", "Status", "Cycle", "Amount", "Monthly cost", "Annual cost", "Next renewal"),
                        list.map { listOf(it.name, it.status.label, it.cycle.label, plain(it.amountMinor, base), plain(it.monthlyCostMinor, base), plain(it.annualCostMinor, base), it.renewalDate.toString()) }, setOf(3, 4, 5))),
                    bars = active.map { it.name to it.annualCostMinor },
                )
            }

            ReportType.TRANSACTIONS -> {
                val items = transactions.listInRange(range)
                val inc = items.filter { it.transaction.type == TransactionType.INCOME }.sumOf { it.transaction.baseAmountMinor }
                val exp = items.filter { it.transaction.type == TransactionType.EXPENSE }.sumOf { it.transaction.baseAmountMinor }
                val rows = items.map { i ->
                    val t = i.transaction
                    val cur = CurrencyInfo.byCode(t.currencyCode)
                    listOf(day(t.occurredAt), t.type.name.lowercase(), t.title, i.categoryName ?: "", if (t.type == TransactionType.TRANSFER) "${i.accountName} -> ${i.transferAccountName}" else i.accountName,
                        plain(t.amountMinor, cur), t.currencyCode, plain(t.baseAmountMinor, base), t.paymentMethod.label, if (t.gstTagged) "yes" else "", t.merchant, t.notes)
                }
                Report(
                    type.label, periodLabel, generated, base.code,
                    summary = listOf("Transactions" to items.size.toString(), "Income" to money(inc), "Expenses" to money(exp)),
                    tables = listOf(ReportTable("Transactions", listOf("Date", "Type", "Title", "Category", "Account", "Amount", "Currency", "Amount (${base.code})", "Payment method", "GST", "Merchant", "Notes"), rows, setOf(5, 7))),
                )
            }
        }
    }

    private fun effect(t: Transaction, accountId: Long): Long = when (t.type) {
        TransactionType.INCOME -> if (t.accountId == accountId) t.amountMinor else 0
        TransactionType.EXPENSE -> if (t.accountId == accountId) -t.amountMinor else 0
        TransactionType.ADJUSTMENT -> if (t.accountId == accountId) t.amountMinor else 0
        TransactionType.TRANSFER -> when (accountId) { t.accountId -> -t.amountMinor; t.transferAccountId -> t.amountMinor; else -> 0 }
    }
}
