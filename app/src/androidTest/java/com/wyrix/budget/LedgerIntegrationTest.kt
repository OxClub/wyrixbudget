package com.wyrix.budget

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.data.local.AppDatabase
import com.wyrix.budget.data.local.DefaultDataSeeder
import com.wyrix.budget.data.repository.AccountRepositoryImpl
import com.wyrix.budget.data.repository.CategoryRepositoryImpl
import com.wyrix.budget.data.repository.TransactionRepositoryImpl
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.ActionResult
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.Transaction
import com.wyrix.budget.domain.model.TransactionType
import com.wyrix.budget.domain.repository.BaseCurrencyProvider
import com.wyrix.budget.domain.usecase.CreateAccountUseCase
import com.wyrix.budget.domain.usecase.DeleteTransactionUseCase
import com.wyrix.budget.domain.usecase.ReconcileAccountUseCase
import com.wyrix.budget.domain.usecase.SaveTransactionUseCase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** Runs the real Room queries against an in-memory database. Needs a device or emulator. */
@RunWith(AndroidJUnit4::class)
class LedgerIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"), ZoneOffset.UTC)
    private lateinit var db: AppDatabase
    private lateinit var accounts: AccountRepositoryImpl
    private lateinit var categories: CategoryRepositoryImpl
    private lateinit var transactions: TransactionRepositoryImpl
    private lateinit var save: SaveTransactionUseCase
    private lateinit var createAccount: CreateAccountUseCase

    private var bankId = 0L
    private var cashId = 0L
    private var salaryId = 0L
    private var groceriesId = 0L

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java)
            .allowMainThreadQueries().build()
        accounts = AccountRepositoryImpl(db.accountDao())
        categories = CategoryRepositoryImpl(db.categoryDao())
        transactions = TransactionRepositoryImpl(db, db.transactionDao(), db.accountDao())
        save = SaveTransactionUseCase(transactions, categories, accounts, object : BaseCurrencyProvider { override suspend fun code() = "INR" }, clock)
        createAccount = CreateAccountUseCase(accounts, clock)
        DefaultDataSeeder(db.categoryDao()).seedIfEmpty()

        bankId = (createAccount("Bank", AccountType.BANK, 5_000_000, "", 0) as ActionResult.Success).value
        cashId = (createAccount("Cash", AccountType.CASH, 0, "", 0) as ActionResult.Success).value
        salaryId = categories.observeByKind(CategoryKind.INCOME).first().first { it.name == "Salary" }.id
        groceriesId = categories.observeByKind(CategoryKind.EXPENSE).first().first { it.name == "Groceries" }.id
    }

    @After fun tearDown() = db.close()

    private suspend fun balance(id: Long) = accounts.getActive(id)!!.balanceMinor

    private suspend fun add(type: TransactionType, amount: Long, account: Long, category: Long? = null, to: Long? = null): Long =
        (save(
            Transaction(
                type = type, amountMinor = amount, title = "", categoryId = category,
                accountId = account, transferAccountId = to, occurredAt = clock.millis(),
            ),
        ) as ActionResult.Success).value

    @Test fun incomeExpenseAndTransferUpdateBalancesCorrectly() = runBlocking {
        add(TransactionType.INCOME, 1_000_000, bankId, salaryId)
        add(TransactionType.EXPENSE, 500_000, bankId, groceriesId)
        add(TransactionType.TRANSFER, 300_000, bankId, to = cashId)

        assertEquals(5_200_000L, balance(bankId)) // 50,000 + 10,000 - 5,000 - 3,000
        assertEquals(300_000L, balance(cashId))
    }

    @Test fun transfersNeverCountAsIncomeOrExpense() = runBlocking {
        add(TransactionType.INCOME, 1_000_000, bankId, salaryId)
        add(TransactionType.EXPENSE, 500_000, bankId, groceriesId)
        add(TransactionType.TRANSFER, 300_000, bankId, to = cashId)

        val totals = transactions.observeTotals(PeriodCalculator.thisMonth(clock.instant(), ZoneOffset.UTC)).first()
        assertEquals(1_000_000L, totals.incomeMinor)
        assertEquals(500_000L, totals.expenseMinor)
    }

    @Test fun editingReplacesThePreviousEffect() = runBlocking {
        val id = add(TransactionType.EXPENSE, 500_000, bankId, groceriesId)
        assertEquals(4_500_000L, balance(bankId))

        val edited = transactions.getById(id)!!.copy(amountMinor = 600_000)
        assertTrue(save(edited) is ActionResult.Success)
        assertEquals(4_400_000L, balance(bankId))
    }

    @Test fun movingATransactionToAnotherAccountMovesTheBalanceEffect() = runBlocking {
        val id = add(TransactionType.EXPENSE, 200_000, bankId, groceriesId)
        save(transactions.getById(id)!!.copy(accountId = cashId))
        assertEquals(5_000_000L, balance(bankId))
        assertEquals(-200_000L, balance(cashId))
    }

    @Test fun deletingRestoresTheBalance() = runBlocking {
        val id = add(TransactionType.EXPENSE, 500_000, bankId, groceriesId)
        assertTrue(DeleteTransactionUseCase(transactions)(id) is ActionResult.Success)
        assertEquals(5_000_000L, balance(bankId))
    }

    @Test fun deletingATransferRestoresBothAccounts() = runBlocking {
        val id = add(TransactionType.TRANSFER, 300_000, bankId, to = cashId)
        DeleteTransactionUseCase(transactions)(id)
        assertEquals(5_000_000L, balance(bankId))
        assertEquals(0L, balance(cashId))
    }

    @Test fun reconciliationRecordsASignedAdjustmentWithoutAffectingTotals() = runBlocking {
        add(TransactionType.INCOME, 1_000_000, bankId, salaryId)
        val reconcile = ReconcileAccountUseCase(accounts, transactions, clock)

        val result = reconcile(bankId, 5_800_000, "Bank charged a fee")
        assertEquals(-200_000L, (result as ActionResult.Success).value)
        assertEquals(5_800_000L, balance(bankId))

        val totals = transactions.observeTotals(PeriodCalculator.thisMonth(clock.instant(), ZoneOffset.UTC)).first()
        assertEquals(1_000_000L, totals.incomeMinor)
        assertEquals(0L, totals.expenseMinor)
    }

    @Test fun reconcilingToTheSameBalanceRecordsNothing() = runBlocking {
        val reconcile = ReconcileAccountUseCase(accounts, transactions, clock)
        assertEquals(0L, (reconcile(bankId, 5_000_000, "Checked") as ActionResult.Success).value)
        assertEquals(5_000_000L, balance(bankId))
    }

    @Test fun duplicateAccountNamesAreRejectedCaseInsensitively() = runBlocking {
        assertTrue(createAccount("bank", AccountType.BANK, 0, "", 0) is ActionResult.Rejected)
    }

    @Test fun seederIsIdempotent() = runBlocking {
        val before = categories.observeAll().first().size
        DefaultDataSeeder(db.categoryDao()).seedIfEmpty()
        assertEquals(before, categories.observeAll().first().size)
    }
}
