package com.wyrix.budget.domain.repository

import com.wyrix.budget.core.time.TimeRange
import com.wyrix.budget.domain.calc.AllocationInput
import com.wyrix.budget.domain.calc.BudgetStatus
import com.wyrix.budget.domain.model.Attachment
import com.wyrix.budget.domain.model.Bill
import com.wyrix.budget.domain.model.BillPayment
import com.wyrix.budget.domain.model.Budget
import com.wyrix.budget.domain.model.ExchangeRate
import com.wyrix.budget.domain.model.GoalContribution
import com.wyrix.budget.domain.model.GoalWithSaved
import com.wyrix.budget.domain.model.Loan
import com.wyrix.budget.domain.model.LoanPayment
import com.wyrix.budget.domain.model.LoanWithOutstanding
import com.wyrix.budget.domain.model.RecurringEntry
import com.wyrix.budget.domain.model.RecurringRule
import com.wyrix.budget.domain.model.SavingsGoal
import com.wyrix.budget.domain.model.Subscription
import com.wyrix.budget.domain.model.SubscriptionPayment
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.ZoneId

interface BudgetRepository {
    fun observeStatuses(today: LocalDate, zone: ZoneId): Flow<List<BudgetStatus>>
    fun observeStatus(id: Long, today: LocalDate, zone: ZoneId): Flow<BudgetStatus?>
    suspend fun get(id: Long): Pair<Budget, List<AllocationInput>>?

    /** Inserts when [budget].id == 0, otherwise updates and replaces allocations. Returns the id. */
    suspend fun save(budget: Budget, allocations: List<AllocationInput>): Long
    suspend fun delete(id: Long)
}

interface GoalRepository {
    fun observeGoals(): Flow<List<GoalWithSaved>>
    fun observeGoal(id: Long): Flow<GoalWithSaved?>
    fun observeContributions(goalId: Long): Flow<List<GoalContribution>>
    suspend fun save(goal: SavingsGoal): Long
    suspend fun delete(id: Long)
    suspend fun addContribution(c: GoalContribution): Long
    suspend fun deleteContribution(id: Long)
}

interface BillRepository {
    fun observeBills(): Flow<List<Bill>>
    fun observePayments(billId: Long): Flow<List<BillPayment>>
    suspend fun get(id: Long): Bill?
    suspend fun save(bill: Bill): Long
    suspend fun delete(id: Long)

    /** Atomically stores the payment and moves the bill on. @return false if that due date was already paid. */
    suspend fun hasPayment(billId: Long, dueDate: LocalDate): Boolean
    suspend fun recordPayment(payment: BillPayment, newNextDue: LocalDate?): Boolean
}

interface SubscriptionRepository {
    fun observeAll(): Flow<List<Subscription>>
    fun observePayments(subscriptionId: Long): Flow<List<SubscriptionPayment>>
    fun observeAllPayments(): Flow<List<SubscriptionPayment>>
    suspend fun get(id: Long): Subscription?
    suspend fun save(subscription: Subscription): Long
    suspend fun delete(id: Long)
    suspend fun hasRenewal(subscriptionId: Long, dueDate: LocalDate): Boolean
    suspend fun recordRenewal(payment: SubscriptionPayment, newRenewalDate: LocalDate): Boolean
}

interface LoanRepository {
    fun observeAll(): Flow<List<LoanWithOutstanding>>
    fun observe(id: Long): Flow<LoanWithOutstanding?>
    fun observePayments(loanId: Long): Flow<List<LoanPayment>>
    suspend fun get(id: Long): LoanWithOutstanding?
    suspend fun save(loan: Loan): Long
    suspend fun delete(id: Long)
    suspend fun recordPayment(payment: LoanPayment, newNextPaymentDate: LocalDate?, closeLoan: Boolean)
}

interface RecurringRepository {
    fun observeRules(): Flow<List<RecurringRule>>
    fun observeEntries(ruleId: Long): Flow<List<RecurringEntry>>
    suspend fun get(id: Long): RecurringRule?
    suspend fun save(rule: RecurringRule): Long
    suspend fun delete(id: Long)

    /** Atomically logs the entry and advances the rule. @return false if that due date was already handled. */
    suspend fun hasEntry(ruleId: Long, dueDate: LocalDate): Boolean
    suspend fun logEntry(entry: RecurringEntry, newNextDate: LocalDate?): Boolean
}

interface AttachmentRepository {
    fun observe(transactionId: Long): Flow<List<Attachment>>
    suspend fun list(transactionId: Long): List<Attachment>
    suspend fun add(attachment: Attachment): Long
    suspend fun get(id: Long): Attachment?
    suspend fun remove(id: Long)
}

interface ExchangeRateRepository {
    fun observeAll(): Flow<List<ExchangeRate>>
    suspend fun get(currencyCode: String): ExchangeRate?
    suspend fun upsert(rate: ExchangeRate)
    suspend fun delete(currencyCode: String)
}

data class CategoryAmount(val categoryId: Long, val name: String, val amountMinor: Long)
data class LabeledAmount(val label: String, val amountMinor: Long)
data class MonthTotals(val month: String, val incomeMinor: Long, val expenseMinor: Long)

interface AnalyticsRepository {
    fun observeByCategory(expense: Boolean, range: TimeRange): Flow<List<CategoryAmount>>
    suspend fun byCategory(expense: Boolean, range: TimeRange): List<CategoryAmount>
    suspend fun byMerchant(range: TimeRange): List<LabeledAmount>
    suspend fun byAccount(range: TimeRange): List<LabeledAmount>
    suspend fun dailySeries(expense: Boolean, range: TimeRange, zone: ZoneId): List<LabeledAmount>
    suspend fun monthlyTotals(range: TimeRange, zone: ZoneId): List<MonthTotals>
    fun observeChangeToken(): Flow<Long>
}
