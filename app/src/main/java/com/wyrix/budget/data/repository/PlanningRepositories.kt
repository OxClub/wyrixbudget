package com.wyrix.budget.data.repository

import androidx.room.withTransaction
import com.wyrix.budget.core.time.TimeRange
import com.wyrix.budget.data.local.AppDatabase
import com.wyrix.budget.data.local.dao.*
import com.wyrix.budget.data.local.entity.*
import com.wyrix.budget.domain.calc.*
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class BudgetRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: BudgetDao,
    private val analytics: AnalyticsDao,
) : BudgetRepository {

    private suspend fun status(b: BudgetEntity, allocs: List<AllocationInput>, today: LocalDate, zone: ZoneId): BudgetStatus {
        val from = b.startDate.toDate().atStartOfDay(zone).toInstant().toEpochMilli()
        val to = b.endDate.toDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val spend = analytics.byCategory("EXPENSE", from, to).map { CategorySpend(it.categoryId, it.name, it.amountMinor) }
        val d = b.toDomain()
        val input = BudgetInput(d.id, d.name, d.totalMinor, d.startDate, d.endDate, d.thresholds, d.countOnlyAllocated)
        return BudgetCalculator.status(input, allocs, spend, today)
    }

    private fun List<AllocationWithBudgetRow>.forBudget(id: Long) =
        filter { it.budgetId == id }.map { AllocationInput(it.categoryId, it.name, it.amountMinor) }

    override fun observeStatuses(today: LocalDate, zone: ZoneId): Flow<List<BudgetStatus>> =
        combine(dao.observeAll(), dao.observeAllAllocations(), analytics.changeToken()) { budgets, allocs, _ -> budgets to allocs }
            .map { (budgets, allocs) -> budgets.map { status(it, allocs.forBudget(it.id), today, zone) } }

    override fun observeStatus(id: Long, today: LocalDate, zone: ZoneId): Flow<BudgetStatus?> =
        combine(dao.observe(id), dao.observeAllAllocations(), analytics.changeToken()) { b, allocs, _ -> b to allocs }
            .map { (b, allocs) -> b?.let { status(it, allocs.forBudget(it.id), today, zone) } }

    override suspend fun get(id: Long): Pair<Budget, List<AllocationInput>>? {
        val b = dao.get(id) ?: return null
        return b.toDomain() to dao.getAllocations(id).map { AllocationInput(it.categoryId, it.name, it.amountMinor) }
    }

    override suspend fun save(budget: Budget, allocations: List<AllocationInput>): Long = db.withTransaction {
        val id = if (budget.id == 0L) dao.insert(budget.toEntity().copy(id = 0)) else {
            dao.update(budget.toEntity()); dao.deleteAllocations(budget.id); budget.id
        }
        dao.insertAllocations(allocations.map { BudgetAllocationEntity(budgetId = id, categoryId = it.categoryId, amountMinor = it.amountMinor) })
        id
    }

    override suspend fun delete(id: Long) { dao.delete(id) }
}

class GoalRepositoryImpl @Inject constructor(private val dao: GoalDao) : GoalRepository {
    override fun observeGoals() = dao.observeAll().map { rows -> rows.map { GoalWithSaved(it.goal.toDomain(), it.savedMinor) } }
    override fun observeGoal(id: Long) = dao.observe(id).map { r -> r?.let { GoalWithSaved(it.goal.toDomain(), it.savedMinor) } }
    override fun observeContributions(goalId: Long) = dao.observeContributions(goalId).map { l -> l.map { it.toDomain() } }
    override suspend fun save(goal: SavingsGoal): Long =
        if (goal.id == 0L) dao.insert(goal.toEntity().copy(id = 0)) else { dao.update(goal.toEntity()); goal.id }
    override suspend fun delete(id: Long) { dao.delete(id) }
    override suspend fun addContribution(c: GoalContribution): Long = dao.insertContribution(c.toEntity().copy(id = 0))
    override suspend fun deleteContribution(id: Long) { dao.deleteContribution(id) }
}

class BillRepositoryImpl @Inject constructor(private val db: AppDatabase, private val dao: BillDao) : BillRepository {
    override fun observeBills() = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override fun observePayments(billId: Long) = dao.observePayments(billId).map { l -> l.map { it.toDomain() } }
    override suspend fun get(id: Long) = dao.get(id)?.toDomain()
    override suspend fun save(bill: Bill): Long =
        if (bill.id == 0L) dao.insert(bill.toEntity().copy(id = 0)) else { dao.update(bill.toEntity()); bill.id }
    override suspend fun delete(id: Long) { dao.delete(id) }
    override suspend fun hasPayment(billId: Long, dueDate: LocalDate) = dao.paymentCount(billId, dueDate.toDay()) > 0

    override suspend fun recordPayment(payment: BillPayment, newNextDue: LocalDate?): Boolean = db.withTransaction {
        if (dao.paymentCount(payment.billId, payment.dueDate.toDay()) > 0) return@withTransaction false
        dao.insertPayment(payment.toEntity().copy(id = 0))
        val bill = dao.get(payment.billId) ?: return@withTransaction false
        dao.update(bill.copy(nextDueDate = newNextDue?.toDay() ?: bill.nextDueDate, isActive = newNextDue != null))
        true
    }
}

class SubscriptionRepositoryImpl @Inject constructor(private val db: AppDatabase, private val dao: SubscriptionDao) : SubscriptionRepository {
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override fun observePayments(subscriptionId: Long) = dao.observePayments(subscriptionId).map { l -> l.map { it.toDomain() } }
    override fun observeAllPayments() = dao.observeAllPayments().map { l -> l.map { it.toDomain() } }
    override suspend fun get(id: Long) = dao.get(id)?.toDomain()
    override suspend fun save(subscription: Subscription): Long =
        if (subscription.id == 0L) dao.insert(subscription.toEntity().copy(id = 0)) else { dao.update(subscription.toEntity()); subscription.id }
    override suspend fun delete(id: Long) { dao.delete(id) }
    override suspend fun hasRenewal(subscriptionId: Long, dueDate: LocalDate) = dao.paymentCount(subscriptionId, dueDate.toDay()) > 0

    override suspend fun recordRenewal(payment: SubscriptionPayment, newRenewalDate: LocalDate): Boolean = db.withTransaction {
        if (dao.paymentCount(payment.subscriptionId, payment.dueDate.toDay()) > 0) return@withTransaction false
        dao.insertPayment(payment.toEntity().copy(id = 0))
        val s = dao.get(payment.subscriptionId) ?: return@withTransaction false
        dao.update(s.copy(renewalDate = newRenewalDate.toDay()))
        true
    }
}

class LoanRepositoryImpl @Inject constructor(private val db: AppDatabase, private val dao: LoanDao) : LoanRepository {
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override fun observe(id: Long) = dao.observe(id).map { it?.toDomain() }
    override fun observePayments(loanId: Long) = dao.observePayments(loanId).map { l -> l.map { it.toDomain() } }
    override suspend fun get(id: Long) = dao.get(id)?.toDomain()
    override suspend fun save(loan: Loan): Long =
        if (loan.id == 0L) dao.insert(loan.toEntity().copy(id = 0)) else { dao.update(loan.toEntity()); loan.id }
    override suspend fun delete(id: Long) { dao.delete(id) }

    override suspend fun recordPayment(payment: LoanPayment, newNextPaymentDate: LocalDate?, closeLoan: Boolean) {
        db.withTransaction {
            dao.insertPayment(payment.toEntity().copy(id = 0))
            val row = dao.get(payment.loanId) ?: return@withTransaction
            dao.update(row.loan.copy(nextPaymentDate = newNextPaymentDate?.toDay() ?: row.loan.nextPaymentDate, isActive = !closeLoan))
        }
    }
}

class RecurringRepositoryImpl @Inject constructor(private val db: AppDatabase, private val dao: RecurringDao) : RecurringRepository {
    override fun observeRules() = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override fun observeEntries(ruleId: Long) = dao.observeEntries(ruleId).map { l -> l.map { it.toDomain() } }
    override suspend fun get(id: Long) = dao.get(id)?.toDomain()
    override suspend fun save(rule: RecurringRule): Long =
        if (rule.id == 0L) dao.insert(rule.toEntity().copy(id = 0)) else { dao.update(rule.toEntity()); rule.id }
    override suspend fun delete(id: Long) { dao.delete(id) }
    override suspend fun hasEntry(ruleId: Long, dueDate: LocalDate) = dao.entryCount(ruleId, dueDate.toDay()) > 0

    override suspend fun logEntry(entry: RecurringEntry, newNextDate: LocalDate?): Boolean = db.withTransaction {
        if (dao.entryCount(entry.ruleId, entry.dueDate.toDay()) > 0) return@withTransaction false
        dao.insertEntry(entry.toEntity().copy(id = 0))
        val rule = dao.get(entry.ruleId) ?: return@withTransaction false
        dao.update(rule.copy(nextDate = newNextDate?.toDay() ?: rule.nextDate, isActive = newNextDate != null))
        true
    }
}

class AttachmentRepositoryImpl @Inject constructor(private val dao: AttachmentDao) : AttachmentRepository {
    override fun observe(transactionId: Long) = dao.observe(transactionId).map { l -> l.map { it.toDomain() } }
    override suspend fun list(transactionId: Long) = dao.list(transactionId).map { it.toDomain() }
    override suspend fun add(attachment: Attachment) = dao.insert(attachment.toEntity().copy(id = 0))
    override suspend fun get(id: Long) = dao.get(id)?.toDomain()
    override suspend fun remove(id: Long) { dao.delete(id) }
}

class ExchangeRateRepositoryImpl @Inject constructor(private val dao: ExchangeRateDao) : ExchangeRateRepository {
    override fun observeAll() = dao.observeAll().map { l -> l.map { it.toDomain() } }
    override suspend fun get(currencyCode: String) = dao.get(currencyCode)?.toDomain()
    override suspend fun upsert(rate: ExchangeRate) {
        dao.upsert(ExchangeRateEntity(currencyCode = rate.currencyCode, rateToBase = rate.rateToBase, asOf = rate.asOf, source = rate.source))
    }
    override suspend fun delete(currencyCode: String) { dao.delete(currencyCode) }
}

class AnalyticsRepositoryImpl @Inject constructor(private val dao: AnalyticsDao) : AnalyticsRepository {
    private fun type(expense: Boolean) = if (expense) "EXPENSE" else "INCOME"
    private fun offset(range: TimeRange, zone: ZoneId) = zone.rules.getOffset(Instant.ofEpochMilli(range.fromMillis)).totalSeconds

    override fun observeByCategory(expense: Boolean, range: TimeRange) =
        dao.observeByCategory(type(expense), range.fromMillis, range.toMillis).map { l -> l.map { CategoryAmount(it.categoryId, it.name, it.amountMinor) } }

    override suspend fun byCategory(expense: Boolean, range: TimeRange) =
        dao.byCategory(type(expense), range.fromMillis, range.toMillis).map { CategoryAmount(it.categoryId, it.name, it.amountMinor) }

    override suspend fun byMerchant(range: TimeRange) = dao.byMerchant(range.fromMillis, range.toMillis).map { LabeledAmount(it.label, it.amountMinor) }
    override suspend fun byAccount(range: TimeRange) = dao.byAccount(range.fromMillis, range.toMillis).map { LabeledAmount(it.label, it.amountMinor) }
    override suspend fun dailySeries(expense: Boolean, range: TimeRange, zone: ZoneId) =
        dao.dailySeries(type(expense), range.fromMillis, range.toMillis, offset(range, zone)).map { LabeledAmount(it.label, it.amountMinor) }
    override suspend fun monthlyTotals(range: TimeRange, zone: ZoneId) =
        dao.monthlyTotals(range.fromMillis, range.toMillis, offset(range, zone)).map { MonthTotals(it.month, it.incomeMinor, it.expenseMinor) }
    override fun observeChangeToken(): Flow<Long> = dao.changeToken()
}
