package com.wyrix.budget.domain.usecase

import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.domain.calc.*
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject

/** Gathers everything scheduled (bills, subscriptions, loan payments, recurring items) for forecasting and calculators. */
class PlanningSnapshot @Inject constructor(
    private val bills: BillRepository,
    private val subscriptions: SubscriptionRepository,
    private val loans: LoanRepository,
    private val recurring: RecurringRepository,
    private val accounts: AccountRepository,
    private val rates: ExchangeRateRepository,
    private val baseCurrency: BaseCurrencyProvider,
) {
    suspend fun scheduledItems(): List<ScheduledItem> {
        val out = ArrayList<ScheduledItem>()
        bills.observeBills().first().filter { it.isActive }.forEach {
            out += ScheduledItem(it.name, -it.amountMinor, it.startDate, it.nextDueDate, it.frequency, endDate = null, source = "Bill")
        }
        subscriptions.observeAll().first().filter { it.status == SubscriptionStatus.ACTIVE }.forEach {
            out += ScheduledItem(it.name, -it.amountMinor, it.startDate, it.renewalDate, it.cycle, source = "Subscription")
        }
        loans.observeAll().first().filter { it.loan.isActive && it.loan.emiMinor > 0 }.forEach { row ->
            val l = row.loan
            val plan = LoanMath.amortize(row.outstandingMinor, l.rateBps, l.emiMinor)
            val projectedEnd = plan?.let { l.nextPaymentDate.plusMonths((it.schedule.size - 1).toLong()) }
            val end = listOfNotNull(l.endDate, projectedEnd).minOrNull()
            out += ScheduledItem("${l.lender} payment", -l.emiMinor, l.nextPaymentDate, l.nextPaymentDate, Frequency.MONTHLY, endDate = end, source = "Loan")
        }
        recurring.observeRules().first().filter { it.isActive }.forEach {
            val signed = if (it.type == TransactionType.INCOME) it.amountMinor else -it.amountMinor
            out += ScheduledItem(it.title, signed, it.startDate, it.nextDate, it.frequency, it.customDays.coerceAtLeast(1), it.endDate, if (it.type == TransactionType.INCOME) "Recurring income" else "Recurring expense")
        }
        return out
    }

    suspend fun balance(): ConsolidatedBalance {
        val base = CurrencyInfo.byCode(baseCurrency.code())
        val rateMap = rates.observeAll().first().associateBy { it.currencyCode }
        return Consolidation.balance(accounts.observeAccounts().first(), base, rateMap)
    }

    suspend fun outflowNext(days: Int, today: LocalDate): Long {
        val f = ForecastEngine.forecast(today, days, 0, scheduledItems())
        return f.outflowMinor
    }
}
