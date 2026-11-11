package com.wyrix.budget.domain

import com.wyrix.budget.domain.calc.AllocationInput
import com.wyrix.budget.domain.calc.BudgetPeriodType
import com.wyrix.budget.domain.calc.BudgetStatus
import com.wyrix.budget.domain.calc.Frequency
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.*
import com.wyrix.budget.domain.usecase.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

private class FakeBills(var bill: Bill) : BillRepository {
    val payments = mutableListOf<BillPayment>()
    override fun observeBills(): Flow<List<Bill>> = flowOf(listOf(bill))
    override fun observePayments(billId: Long): Flow<List<BillPayment>> = flowOf(payments)
    override suspend fun get(id: Long) = bill.takeIf { it.id == id }
    override suspend fun save(bill: Bill): Long { this.bill = bill; return bill.id }
    override suspend fun delete(id: Long) = Unit
    override suspend fun hasPayment(billId: Long, dueDate: LocalDate) = payments.any { it.billId == billId && it.dueDate == dueDate }
    override suspend fun recordPayment(payment: BillPayment, newNextDue: LocalDate?): Boolean {
        if (hasPayment(payment.billId, payment.dueDate)) return false
        payments += payment
        bill = bill.copy(nextDueDate = newNextDue ?: bill.nextDueDate, isActive = newNextDue != null)
        return true
    }
}

private class FakeLoans(var row: LoanWithOutstanding) : LoanRepository {
    val payments = mutableListOf<LoanPayment>()
    private fun current() = row.loan.let { l -> val paid = payments.sumOf { it.principalPartMinor }; LoanWithOutstanding(l, l.outstandingStartMinor - paid, paid) }
    override fun observeAll(): Flow<List<LoanWithOutstanding>> = flowOf(listOf(current()))
    override fun observe(id: Long): Flow<LoanWithOutstanding?> = flowOf(current())
    override fun observePayments(loanId: Long): Flow<List<LoanPayment>> = flowOf(payments)
    override suspend fun get(id: Long) = current()
    override suspend fun save(loan: Loan): Long { row = row.copy(loan = loan); return loan.id }
    override suspend fun delete(id: Long) = Unit
    override suspend fun recordPayment(payment: LoanPayment, newNextPaymentDate: LocalDate?, closeLoan: Boolean) {
        payments += payment
        row = row.copy(loan = row.loan.copy(nextPaymentDate = newNextPaymentDate ?: row.loan.nextPaymentDate, isActive = !closeLoan))
    }
}

private class FakeRecurring(var rule: RecurringRule) : RecurringRepository {
    val entries = mutableListOf<RecurringEntry>()
    override fun observeRules(): Flow<List<RecurringRule>> = flowOf(listOf(rule))
    override fun observeEntries(ruleId: Long): Flow<List<RecurringEntry>> = flowOf(entries)
    override suspend fun get(id: Long) = rule.takeIf { it.id == id }
    override suspend fun save(rule: RecurringRule): Long { this.rule = rule; return rule.id }
    override suspend fun delete(id: Long) = Unit
    override suspend fun hasEntry(ruleId: Long, dueDate: LocalDate) = entries.any { it.dueDate == dueDate }
    override suspend fun logEntry(entry: RecurringEntry, newNextDate: LocalDate?): Boolean {
        if (hasEntry(entry.ruleId, entry.dueDate)) return false
        entries += entry
        rule = rule.copy(nextDate = newNextDate ?: rule.nextDate, isActive = newNextDate != null)
        return true
    }
}

private class FakeGoals : GoalRepository {
    val saved = mutableListOf<SavingsGoal>(); val contributions = mutableListOf<GoalContribution>()
    override fun observeGoals(): Flow<List<GoalWithSaved>> = flowOf(emptyList())
    override fun observeGoal(id: Long): Flow<GoalWithSaved?> = flowOf(null)
    override fun observeContributions(goalId: Long): Flow<List<GoalContribution>> = flowOf(contributions)
    override suspend fun save(goal: SavingsGoal): Long { saved += goal; return goal.id }
    override suspend fun delete(id: Long) = Unit
    override suspend fun addContribution(c: GoalContribution): Long { contributions += c; return contributions.size.toLong() }
    override suspend fun deleteContribution(id: Long) = Unit
}

private class FakeBudgets : BudgetRepository {
    var lastSaved: Pair<Budget, List<AllocationInput>>? = null
    override fun observeStatuses(today: LocalDate, zone: ZoneId): Flow<List<BudgetStatus>> = flowOf(emptyList())
    override fun observeStatus(id: Long, today: LocalDate, zone: ZoneId): Flow<BudgetStatus?> = flowOf(null)
    override suspend fun get(id: Long) = lastSaved
    override suspend fun save(budget: Budget, allocations: List<AllocationInput>): Long { lastSaved = budget to allocations; return 1 }
    override suspend fun delete(id: Long) = Unit
}

class PlanningUseCasesTest {
    private val now = Instant.parse("2026-11-09T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val expenseCategory = Category(id = 2, name = "Rent", kind = CategoryKind.EXPENSE, colorArgb = 0)
    private val loanCategory = Category(id = 3, name = "Loan payments", kind = CategoryKind.EXPENSE, colorArgb = 0)
    private lateinit var txRepo: FakeTransactions
    private lateinit var save: SaveTransactionUseCase
    private val categories = FakeCategories(listOf(expenseCategory, loanCategory))

    @Before fun setUp() {
        txRepo = FakeTransactions()
        save = SaveTransactionUseCase(txRepo, categories, FakeAccounts(1), InrBase, clock)
    }

    // ---- bills ---------------------------------------------------------------------------

    private fun monthlyBill(categoryId: Long? = 2) = Bill(7, "Rent", 25_000, categoryId, 1, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 10), Frequency.MONTHLY, listOf(3), true)

    @Test fun payingABillMovesItToTheNextDueDate() = runTest {
        val repo = FakeBills(monthlyBill())
        val r = PayBillUseCase(repo, save, clock)(7, createExpense = false, accountId = null, paidOn = LocalDate.of(2026, 11, 9))
        assertTrue(r is ActionResult.Success)
        assertEquals(LocalDate.of(2026, 12, 10), repo.bill.nextDueDate)
        assertTrue(repo.bill.isActive)
        assertEquals(1, repo.payments.size)
        assertTrue(txRepo.stored.isEmpty())
    }

    @Test fun payingWithAnExpenseRecordsItAndLinksThePayment() = runTest {
        val repo = FakeBills(monthlyBill())
        PayBillUseCase(repo, save, clock)(7, createExpense = true, accountId = 1, paidOn = LocalDate.of(2026, 11, 9))
        val tx = txRepo.stored.values.single()
        assertEquals(25_000L, tx.amountMinor); assertEquals(TransactionType.EXPENSE, tx.type); assertEquals(2L, tx.categoryId)
        assertEquals(tx.id, repo.payments.single().transactionId)
    }

    @Test fun aDueDateCanOnlyBePaidOnce() = runTest {
        val repo = FakeBills(monthlyBill())
        repo.payments += BillPayment(billId = 7, dueDate = LocalDate.of(2026, 11, 10), paidAt = 0, amountMinor = 25_000, transactionId = null)
        val r = PayBillUseCase(repo, save, clock)(7, true, 1, LocalDate.of(2026, 11, 9))
        assertTrue(r is ActionResult.Rejected)
        assertTrue(txRepo.stored.isEmpty()) // no orphan expense
    }

    @Test fun aOneTimeBillBecomesPaidAndInactive() = runTest {
        val repo = FakeBills(monthlyBill().copy(frequency = Frequency.ONCE))
        PayBillUseCase(repo, save, clock)(7, false, null, LocalDate.of(2026, 11, 9))
        assertFalse(repo.bill.isActive)
        assertTrue(PayBillUseCase(repo, save, clock)(7, false, null, LocalDate.of(2026, 11, 9)) is ActionResult.Rejected)
    }

    @Test fun recordingAnExpenseNeedsACategory() = runTest {
        val repo = FakeBills(monthlyBill(categoryId = null))
        assertTrue(PayBillUseCase(repo, save, clock)(7, true, 1, LocalDate.of(2026, 11, 9)) is ActionResult.Rejected)
        assertTrue(repo.payments.isEmpty())
    }

    // ---- loans ---------------------------------------------------------------------------

    private fun loanRow() = LoanWithOutstanding(
        Loan(4, "Bank", LoanType.PERSONAL, 2_000_000, 1_000_000, 1200, 100_000, Frequency.MONTHLY, LocalDate.of(2026, 10, 15), null, LocalDate.of(2026, 11, 15), 1, true), 1_000_000, 0,
    )

    @Test fun aLoanPaymentIsSplitIntoInterestAndPrincipal() = runTest {
        val repo = FakeLoans(loanRow())
        val r = RecordLoanPaymentUseCase(repo, categories, save)(4, 100_000, LocalDate.of(2026, 11, 15), createExpense = false, accountId = null)
        assertTrue(r is ActionResult.Success)
        val p = repo.payments.single()
        assertEquals(10_000L, p.interestPartMinor)   // 1% of 10,000
        assertEquals(90_000L, p.principalPartMinor)
        assertEquals(910_000L, repo.row.outstandingMinor.let { 1_000_000L - p.principalPartMinor })
        assertEquals(LocalDate.of(2026, 12, 15), repo.row.loan.nextPaymentDate)
    }

    @Test fun payingMoreThanOwedIsRejected() = runTest {
        val repo = FakeLoans(loanRow())
        assertTrue(RecordLoanPaymentUseCase(repo, categories, save)(4, 1_010_001, LocalDate.of(2026, 11, 15), false, null) is ActionResult.Rejected)
        assertTrue(repo.payments.isEmpty())
    }

    @Test fun clearingTheBalanceClosesTheLoan() = runTest {
        val repo = FakeLoans(loanRow())
        RecordLoanPaymentUseCase(repo, categories, save)(4, 1_010_000, LocalDate.of(2026, 11, 15), false, null)
        assertFalse(repo.row.loan.isActive)
    }

    @Test fun aLoanPaymentCanAlsoBeRecordedAsAnExpense() = runTest {
        val repo = FakeLoans(loanRow())
        RecordLoanPaymentUseCase(repo, categories, save)(4, 100_000, LocalDate.of(2026, 11, 15), createExpense = true, accountId = 1)
        val tx = txRepo.stored.values.single()
        assertEquals(3L, tx.categoryId); assertEquals(100_000L, tx.amountMinor)
        assertEquals(tx.id, repo.payments.single().transactionId)
    }

    // ---- recurring -----------------------------------------------------------------------

    private fun salaryRule(end: LocalDate? = null) = RecurringRule(
        9, "Salary", TransactionType.EXPENSE, 50_000, 2, 1, Frequency.MONTHLY, 1, LocalDate.of(2026, 10, 31), LocalDate.of(2026, 10, 31), end, true,
    )

    @Test fun confirmingRecordsTheTransactionAndAdvancesTheRule() = runTest {
        val repo = FakeRecurring(salaryRule())
        val r = HandleRecurringUseCase(repo, save, clock).confirm(9, LocalDate.of(2026, 10, 31))
        assertTrue(r is ActionResult.Success)
        assertEquals(1, txRepo.stored.size)
        assertEquals(LocalDate.of(2026, 11, 30), repo.rule.nextDate) // anchored on the 31st, clamped to month end
        assertEquals(EntryStatus.CONFIRMED, repo.entries.single().status)
    }

    @Test fun skippingRecordsNothing() = runTest {
        val repo = FakeRecurring(salaryRule())
        HandleRecurringUseCase(repo, save, clock).skip(9, LocalDate.of(2026, 10, 31))
        assertTrue(txRepo.stored.isEmpty())
        assertEquals(EntryStatus.SKIPPED, repo.entries.single().status)
    }

    @Test fun occurrencesMustBeHandledInOrderAndOnlyOnce() = runTest {
        val repo = FakeRecurring(salaryRule())
        val h = HandleRecurringUseCase(repo, save, clock)
        assertTrue(h.confirm(9, LocalDate.of(2026, 11, 30)) is ActionResult.Rejected)   // not the earliest
        h.confirm(9, LocalDate.of(2026, 10, 31))
        assertTrue(h.confirm(9, LocalDate.of(2026, 10, 31)) is ActionResult.Rejected)   // already handled
        assertEquals(1, txRepo.stored.size)
    }

    @Test fun theRuleStopsAtItsEndDate() = runTest {
        val repo = FakeRecurring(salaryRule(end = LocalDate.of(2026, 11, 15)))
        HandleRecurringUseCase(repo, save, clock).confirm(9, LocalDate.of(2026, 10, 31))
        assertFalse(repo.rule.isActive)
    }

    // ---- goals ---------------------------------------------------------------------------

    private val goal = SavingsGoal(5, "Laptop", 90_000, null, GoalPriority.MEDIUM, "", GoalStatus.ACTIVE)

    @Test fun reachingTheTargetCompletesTheGoal() = runTest {
        val repo = FakeGoals()
        val r = AddContributionUseCase(repo)(GoalWithSaved(goal, 80_000), 10_000, null, 0, "")
        assertTrue(r is ActionResult.Success)
        assertEquals(GoalStatus.COMPLETED, repo.saved.last().status)
    }

    @Test fun youCannotWithdrawMoreThanSaved() = runTest {
        val repo = FakeGoals()
        assertTrue(AddContributionUseCase(repo)(GoalWithSaved(goal, 5_000), -6_000, null, 0, "") is ActionResult.Rejected)
        assertTrue(repo.contributions.isEmpty())
    }

    @Test fun withdrawingFromACompletedGoalReopensIt() = runTest {
        val repo = FakeGoals()
        AddContributionUseCase(repo)(GoalWithSaved(goal.copy(status = GoalStatus.COMPLETED), 90_000), -1_000, null, 0, "")
        assertEquals(GoalStatus.ACTIVE, repo.saved.last().status)
    }

    // ---- budgets -------------------------------------------------------------------------

    private fun budget(total: Long = 30_000, end: LocalDate = LocalDate.of(2026, 11, 30)) =
        Budget(0, "Nov", BudgetPeriodType.MONTHLY, LocalDate.of(2026, 11, 1), end, total, emptyList(), false)

    @Test fun aValidBudgetIsSavedWithDefaultAlerts() = runTest {
        val repo = FakeBudgets()
        val r = SaveBudgetUseCase(repo, clock)(budget(), listOf(AllocationInput(1, "Food", 10_000)))
        assertTrue(r is ActionResult.Success)
        assertEquals(listOf(50, 75, 90, 100), repo.lastSaved!!.first.thresholds)
        assertEquals(now.toEpochMilli(), repo.lastSaved!!.first.createdAt)
    }

    @Test fun budgetRulesAreEnforced() = runTest {
        val uc = SaveBudgetUseCase(FakeBudgets(), clock)
        assertTrue(uc(budget(total = 0), emptyList()) is ActionResult.Rejected)
        assertTrue(uc(budget(end = LocalDate.of(2026, 10, 1)), emptyList()) is ActionResult.Rejected)
        assertTrue(uc(budget(), listOf(AllocationInput(1, "Food", 20_000), AllocationInput(2, "Fuel", 20_000))) is ActionResult.Rejected) // exceeds total
        assertTrue(uc(budget(), listOf(AllocationInput(1, "Food", 5_000), AllocationInput(1, "Food", 5_000))) is ActionResult.Rejected)   // duplicate category
        assertTrue(uc(budget().copy(name = " "), emptyList()) is ActionResult.Rejected)
    }
}
