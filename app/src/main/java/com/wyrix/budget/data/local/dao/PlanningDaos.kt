package com.wyrix.budget.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wyrix.budget.data.local.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface BudgetDao {
    @Insert suspend fun insert(b: BudgetEntity): Long
    @Update suspend fun update(b: BudgetEntity): Int
    @Query("DELETE FROM budgets WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("SELECT * FROM budgets ORDER BY startDate DESC, id DESC") fun observeAll(): Flow<List<BudgetEntity>>
    @Query("SELECT * FROM budgets WHERE id = :id") fun observe(id: Long): Flow<BudgetEntity?>
    @Query("SELECT * FROM budgets WHERE id = :id") suspend fun get(id: Long): BudgetEntity?

    @Insert suspend fun insertAllocations(list: List<BudgetAllocationEntity>)
    @Query("DELETE FROM budget_allocations WHERE budgetId = :budgetId") suspend fun deleteAllocations(budgetId: Long)

    @Query(
        "SELECT a.categoryId AS categoryId, c.name AS name, a.amountMinor AS amountMinor " +
            "FROM budget_allocations a JOIN categories c ON c.id = a.categoryId WHERE a.budgetId = :budgetId ORDER BY a.amountMinor DESC",
    )
    suspend fun getAllocations(budgetId: Long): List<AllocationRow>

    @Query(
        "SELECT a.budgetId AS budgetId, a.categoryId AS categoryId, c.name AS name, a.amountMinor AS amountMinor " +
            "FROM budget_allocations a JOIN categories c ON c.id = a.categoryId",
    )
    fun observeAllAllocations(): Flow<List<AllocationWithBudgetRow>>
}

@Dao
interface GoalDao {
    @Insert suspend fun insert(g: SavingsGoalEntity): Long
    @Update suspend fun update(g: SavingsGoalEntity): Int
    @Query("DELETE FROM savings_goals WHERE id = :id") suspend fun delete(id: Long): Int

    @Query(
        "SELECT g.*, COALESCE((SELECT SUM(c.amountMinor) FROM savings_contributions c WHERE c.goalId = g.id), 0) AS savedMinor " +
            "FROM savings_goals g ORDER BY CASE g.status WHEN 'ACTIVE' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END, g.id DESC",
    )
    fun observeAll(): Flow<List<GoalRow>>

    @Query(
        "SELECT g.*, COALESCE((SELECT SUM(c.amountMinor) FROM savings_contributions c WHERE c.goalId = g.id), 0) AS savedMinor " +
            "FROM savings_goals g WHERE g.id = :id",
    )
    fun observe(id: Long): Flow<GoalRow?>

    @Query("SELECT * FROM savings_contributions WHERE goalId = :goalId ORDER BY occurredAt DESC, id DESC")
    fun observeContributions(goalId: Long): Flow<List<SavingsContributionEntity>>

    @Insert suspend fun insertContribution(c: SavingsContributionEntity): Long
    @Query("DELETE FROM savings_contributions WHERE id = :id") suspend fun deleteContribution(id: Long): Int
}

@Dao
interface BillDao {
    @Insert suspend fun insert(b: BillEntity): Long
    @Update suspend fun update(b: BillEntity): Int
    @Query("DELETE FROM bills WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("SELECT * FROM bills ORDER BY isActive DESC, nextDueDate ASC, id") fun observeAll(): Flow<List<BillEntity>>
    @Query("SELECT * FROM bills WHERE id = :id") suspend fun get(id: Long): BillEntity?
    @Query("SELECT * FROM bill_payments WHERE billId = :billId ORDER BY dueDate DESC") fun observePayments(billId: Long): Flow<List<BillPaymentEntity>>
    @Query("SELECT COUNT(*) FROM bill_payments WHERE billId = :billId AND dueDate = :dueDate") suspend fun paymentCount(billId: Long, dueDate: Long): Int
    @Insert suspend fun insertPayment(p: BillPaymentEntity): Long
}

@Dao
interface SubscriptionDao {
    @Insert suspend fun insert(s: SubscriptionEntity): Long
    @Update suspend fun update(s: SubscriptionEntity): Int
    @Query("DELETE FROM subscriptions WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("SELECT * FROM subscriptions ORDER BY CASE status WHEN 'ACTIVE' THEN 0 WHEN 'PAUSED' THEN 1 ELSE 2 END, renewalDate") fun observeAll(): Flow<List<SubscriptionEntity>>
    @Query("SELECT * FROM subscriptions WHERE id = :id") suspend fun get(id: Long): SubscriptionEntity?
    @Query("SELECT * FROM subscription_payments WHERE subscriptionId = :id ORDER BY dueDate DESC") fun observePayments(id: Long): Flow<List<SubscriptionPaymentEntity>>
    @Query("SELECT * FROM subscription_payments ORDER BY dueDate DESC") fun observeAllPayments(): Flow<List<SubscriptionPaymentEntity>>
    @Query("SELECT COUNT(*) FROM subscription_payments WHERE subscriptionId = :id AND dueDate = :dueDate") suspend fun paymentCount(id: Long, dueDate: Long): Int
    @Insert suspend fun insertPayment(p: SubscriptionPaymentEntity): Long
}

@Dao
interface LoanDao {
    @Insert suspend fun insert(l: LoanEntity): Long
    @Update suspend fun update(l: LoanEntity): Int
    @Query("DELETE FROM loans WHERE id = :id") suspend fun delete(id: Long): Int

    @Query(
        "SELECT l.*, l.outstandingStartMinor - COALESCE((SELECT SUM(p.principalPartMinor) FROM loan_payments p WHERE p.loanId = l.id), 0) AS outstandingMinor " +
            "FROM loans l ORDER BY l.isActive DESC, l.nextPaymentDate, l.id",
    )
    fun observeAll(): Flow<List<LoanRow>>

    @Query(
        "SELECT l.*, l.outstandingStartMinor - COALESCE((SELECT SUM(p.principalPartMinor) FROM loan_payments p WHERE p.loanId = l.id), 0) AS outstandingMinor " +
            "FROM loans l WHERE l.id = :id",
    )
    fun observe(id: Long): Flow<LoanRow?>

    @Query(
        "SELECT l.*, l.outstandingStartMinor - COALESCE((SELECT SUM(p.principalPartMinor) FROM loan_payments p WHERE p.loanId = l.id), 0) AS outstandingMinor " +
            "FROM loans l WHERE l.id = :id",
    )
    suspend fun get(id: Long): LoanRow?

    @Query("SELECT * FROM loan_payments WHERE loanId = :id ORDER BY paidDate DESC, id DESC") fun observePayments(id: Long): Flow<List<LoanPaymentEntity>>
    @Insert suspend fun insertPayment(p: LoanPaymentEntity): Long
}

@Dao
interface RecurringDao {
    @Insert suspend fun insert(r: RecurringRuleEntity): Long
    @Update suspend fun update(r: RecurringRuleEntity): Int
    @Query("DELETE FROM recurring_rules WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("SELECT * FROM recurring_rules ORDER BY isActive DESC, nextDate, id") fun observeAll(): Flow<List<RecurringRuleEntity>>
    @Query("SELECT * FROM recurring_rules WHERE id = :id") suspend fun get(id: Long): RecurringRuleEntity?
    @Query("SELECT * FROM recurring_entries WHERE ruleId = :ruleId ORDER BY dueDate DESC") fun observeEntries(ruleId: Long): Flow<List<RecurringEntryEntity>>
    @Query("SELECT COUNT(*) FROM recurring_entries WHERE ruleId = :ruleId AND dueDate = :dueDate") suspend fun entryCount(ruleId: Long, dueDate: Long): Int
    @Insert suspend fun insertEntry(e: RecurringEntryEntity): Long
}

@Dao
interface AttachmentDao {
    @Insert suspend fun insert(a: AttachmentEntity): Long
    @Query("SELECT * FROM attachments WHERE transactionId = :tid ORDER BY id") fun observe(tid: Long): Flow<List<AttachmentEntity>>
    @Query("SELECT * FROM attachments WHERE transactionId = :tid ORDER BY id") suspend fun list(tid: Long): List<AttachmentEntity>
    @Query("SELECT * FROM attachments WHERE id = :id") suspend fun get(id: Long): AttachmentEntity?
    @Query("DELETE FROM attachments WHERE id = :id") suspend fun delete(id: Long): Int
    @Query("SELECT filePath FROM attachments") suspend fun allPaths(): List<String>
}

@Dao
interface ExchangeRateDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsert(r: ExchangeRateEntity): Long
    @Query("SELECT * FROM exchange_rates ORDER BY currencyCode") fun observeAll(): Flow<List<ExchangeRateEntity>>
    @Query("SELECT * FROM exchange_rates WHERE currencyCode = :code") suspend fun get(code: String): ExchangeRateEntity?
    @Query("DELETE FROM exchange_rates WHERE currencyCode = :code") suspend fun delete(code: String): Int
}

@Dao
interface AnalyticsDao {
    @Query(
        "SELECT c.id AS categoryId, c.name AS name, COALESCE(SUM(t.baseAmountMinor), 0) AS amountMinor " +
            "FROM transactions t JOIN categories c ON c.id = t.categoryId " +
            "WHERE t.type = :type AND t.occurredAt >= :from AND t.occurredAt < :to GROUP BY c.id, c.name ORDER BY amountMinor DESC",
    )
    fun observeByCategory(type: String, from: Long, to: Long): Flow<List<CategoryAmountRow>>

    @Query(
        "SELECT c.id AS categoryId, c.name AS name, COALESCE(SUM(t.baseAmountMinor), 0) AS amountMinor " +
            "FROM transactions t JOIN categories c ON c.id = t.categoryId " +
            "WHERE t.type = :type AND t.occurredAt >= :from AND t.occurredAt < :to GROUP BY c.id, c.name ORDER BY amountMinor DESC",
    )
    suspend fun byCategory(type: String, from: Long, to: Long): List<CategoryAmountRow>

    @Query(
        "SELECT CASE WHEN TRIM(merchant) = '' THEN 'Unspecified' ELSE TRIM(merchant) END AS label, SUM(baseAmountMinor) AS amountMinor " +
            "FROM transactions WHERE type = 'EXPENSE' AND occurredAt >= :from AND occurredAt < :to " +
            "GROUP BY lower(TRIM(merchant)) ORDER BY amountMinor DESC LIMIT 10",
    )
    suspend fun byMerchant(from: Long, to: Long): List<LabeledAmountRow>

    @Query(
        "SELECT a.name AS label, SUM(t.baseAmountMinor) AS amountMinor FROM transactions t JOIN accounts a ON a.id = t.accountId " +
            "WHERE t.type = 'EXPENSE' AND t.occurredAt >= :from AND t.occurredAt < :to GROUP BY a.id, a.name ORDER BY amountMinor DESC",
    )
    suspend fun byAccount(from: Long, to: Long): List<LabeledAmountRow>

    @Query(
        "SELECT strftime('%Y-%m-%d', occurredAt / 1000 + :offsetSeconds, 'unixepoch') AS label, SUM(baseAmountMinor) AS amountMinor " +
            "FROM transactions WHERE type = :type AND occurredAt >= :from AND occurredAt < :to GROUP BY label ORDER BY label",
    )
    suspend fun dailySeries(type: String, from: Long, to: Long, offsetSeconds: Int): List<LabeledAmountRow>

    @Query(
        "SELECT strftime('%Y-%m', occurredAt / 1000 + :offsetSeconds, 'unixepoch') AS month, " +
            "COALESCE(SUM(CASE WHEN type = 'INCOME' THEN baseAmountMinor END), 0) AS incomeMinor, " +
            "COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN baseAmountMinor END), 0) AS expenseMinor " +
            "FROM transactions WHERE occurredAt >= :from AND occurredAt < :to GROUP BY month ORDER BY month",
    )
    suspend fun monthlyTotals(from: Long, to: Long, offsetSeconds: Int): List<MonthTotalsRow>

    /** Emits whenever any transaction changes; used to refresh derived figures. */
    @Query("SELECT COUNT(*) + COALESCE(SUM(amountMinor), 0) FROM transactions")
    fun changeToken(): Flow<Long>
}

/** Bulk access used only by backup, restore and data deletion. */
@Dao
interface BackupDao {
    @Query("SELECT * FROM accounts") suspend fun accounts(): List<AccountEntity>
    @Query("SELECT * FROM categories") suspend fun categories(): List<CategoryEntity>
    @Query("SELECT * FROM transactions") suspend fun transactions(): List<TransactionEntity>
    @Query("SELECT * FROM budgets") suspend fun budgets(): List<BudgetEntity>
    @Query("SELECT * FROM budget_allocations") suspend fun allocations(): List<BudgetAllocationEntity>
    @Query("SELECT * FROM savings_goals") suspend fun goals(): List<SavingsGoalEntity>
    @Query("SELECT * FROM savings_contributions") suspend fun contributions(): List<SavingsContributionEntity>
    @Query("SELECT * FROM bills") suspend fun bills(): List<BillEntity>
    @Query("SELECT * FROM bill_payments") suspend fun billPayments(): List<BillPaymentEntity>
    @Query("SELECT * FROM subscriptions") suspend fun subscriptions(): List<SubscriptionEntity>
    @Query("SELECT * FROM subscription_payments") suspend fun subscriptionPayments(): List<SubscriptionPaymentEntity>
    @Query("SELECT * FROM loans") suspend fun loans(): List<LoanEntity>
    @Query("SELECT * FROM loan_payments") suspend fun loanPayments(): List<LoanPaymentEntity>
    @Query("SELECT * FROM recurring_rules") suspend fun rules(): List<RecurringRuleEntity>
    @Query("SELECT * FROM recurring_entries") suspend fun entries(): List<RecurringEntryEntity>
    @Query("SELECT * FROM exchange_rates") suspend fun rates(): List<ExchangeRateEntity>

    @Insert suspend fun putAccounts(l: List<AccountEntity>)
    @Insert suspend fun putCategories(l: List<CategoryEntity>)
    @Insert suspend fun putTransactions(l: List<TransactionEntity>)
    @Insert suspend fun putBudgets(l: List<BudgetEntity>)
    @Insert suspend fun putAllocations(l: List<BudgetAllocationEntity>)
    @Insert suspend fun putGoals(l: List<SavingsGoalEntity>)
    @Insert suspend fun putContributions(l: List<SavingsContributionEntity>)
    @Insert suspend fun putBills(l: List<BillEntity>)
    @Insert suspend fun putBillPayments(l: List<BillPaymentEntity>)
    @Insert suspend fun putSubscriptions(l: List<SubscriptionEntity>)
    @Insert suspend fun putSubscriptionPayments(l: List<SubscriptionPaymentEntity>)
    @Insert suspend fun putLoans(l: List<LoanEntity>)
    @Insert suspend fun putLoanPayments(l: List<LoanPaymentEntity>)
    @Insert suspend fun putRules(l: List<RecurringRuleEntity>)
    @Insert suspend fun putEntries(l: List<RecurringEntryEntity>)
    @Insert suspend fun putRates(l: List<ExchangeRateEntity>)
}
