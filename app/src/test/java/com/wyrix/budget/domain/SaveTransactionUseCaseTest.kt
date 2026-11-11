package com.wyrix.budget.domain

import androidx.paging.PagingData
import com.wyrix.budget.core.time.TimeRange
import com.wyrix.budget.domain.model.Account
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.ActionResult
import com.wyrix.budget.domain.model.Category
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.PeriodTotals
import com.wyrix.budget.domain.model.Transaction
import com.wyrix.budget.domain.model.TransactionFilter
import com.wyrix.budget.domain.model.TransactionListItem
import com.wyrix.budget.domain.model.TransactionType
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.BaseCurrencyProvider
import com.wyrix.budget.domain.repository.CategoryRepository
import com.wyrix.budget.domain.repository.TransactionRepository
import com.wyrix.budget.domain.usecase.DuplicateTransactionUseCase
import com.wyrix.budget.domain.usecase.SaveTransactionUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

internal object InrBase : BaseCurrencyProvider {
    override suspend fun code() = "INR"
}

internal class FakeAccounts(vararg activeIds: Long, private val currencies: Map<Long, String> = emptyMap()) : AccountRepository {
    private val active = activeIds.toSet()
    override fun observeAccounts(): Flow<List<AccountWithBalance>> = flowOf(emptyList())
    override fun observeAccount(id: Long): Flow<AccountWithBalance?> = flowOf(null)
    override suspend fun getActive(id: Long): AccountWithBalance? =
        if (id in active) {
            AccountWithBalance(
                Account(id = id, name = "A$id", type = AccountType.BANK, currencyCode = currencies[id] ?: "INR", openingBalanceMinor = 0, colorArgb = 0, createdAt = 0),
                balanceMinor = 0,
            )
        } else null
    override suspend fun create(account: Account): Long = 1
    override suspend fun archive(id: Long) = Unit
}

internal class FakeCategories(private val all: List<Category>) : CategoryRepository {
    override fun observeByKind(kind: CategoryKind): Flow<List<Category>> = flowOf(all.filter { it.kind == kind })
    override fun observeAll(): Flow<List<Category>> = flowOf(all)
    override suspend fun getById(id: Long): Category? = all.firstOrNull { it.id == id }
    override suspend fun count(kind: CategoryKind): Int = all.count { it.kind == kind }
    override suspend fun findByName(kind: CategoryKind, name: String): Category? = all.firstOrNull { it.kind == kind && it.name.equals(name, true) }
    override suspend fun create(category: Category): Long = 99
    override suspend fun rename(id: Long, name: String) = Unit
    override suspend fun deleteIfUnused(id: Long): Boolean = true
}

internal class FakeTransactions : TransactionRepository {
    val stored = linkedMapOf<Long, Transaction>()
    private var nextId = 1L
    override fun paged(filter: TransactionFilter): Flow<PagingData<TransactionListItem>> = flowOf(PagingData.empty())
    override fun observeRecent(limit: Int): Flow<List<TransactionListItem>> = flowOf(emptyList())
    override fun observeTotals(range: TimeRange): Flow<PeriodTotals> = flowOf(PeriodTotals(0, 0))
    override suspend fun listInRange(range: TimeRange): List<TransactionListItem> = emptyList()
    override suspend fun getById(id: Long): Transaction? = stored[id]
    override suspend fun insert(transaction: Transaction): Long {
        val id = nextId++
        stored[id] = transaction.copy(id = id)
        return id
    }
    override suspend fun update(transaction: Transaction): Boolean {
        if (transaction.id !in stored) return false
        stored[transaction.id] = transaction
        return true
    }
    override suspend fun delete(id: Long): Boolean = stored.remove(id) != null
    override suspend fun reconcile(accountId: Long, actualBalanceMinor: Long, reason: String, nowMillis: Long): Long = 0
}

class SaveTransactionUseCaseTest {
    private val now = Instant.parse("2026-11-08T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val salary = Category(id = 1, name = "Salary", kind = CategoryKind.INCOME, colorArgb = 0)
    private val groceries = Category(id = 2, name = "Groceries", kind = CategoryKind.EXPENSE, colorArgb = 0)

    private lateinit var repo: FakeTransactions
    private lateinit var save: SaveTransactionUseCase

    @Before fun setUp() {
        repo = FakeTransactions()
        save = SaveTransactionUseCase(repo, FakeCategories(listOf(salary, groceries)), FakeAccounts(1, 2), InrBase, clock)
    }

    private fun expense(title: String = "", amount: Long = 25_000, categoryId: Long? = 2, account: Long = 1) = Transaction(
        type = TransactionType.EXPENSE, amountMinor = amount, title = title,
        categoryId = categoryId, accountId = account, occurredAt = now.toEpochMilli(),
    )

    @Test fun savesAnExpenseAndStampsTimes() = runTest {
        val result = save(expense(title = "  Weekly shop  "))
        val id = (result as ActionResult.Success).value
        val stored = repo.stored.getValue(id)
        assertEquals("Weekly shop", stored.title)
        assertEquals(now.toEpochMilli(), stored.createdAt)
        assertEquals(now.toEpochMilli(), stored.updatedAt)
    }

    @Test fun blankTitleDefaultsToCategoryName() = runTest {
        val id = (save(expense(title = "")) as ActionResult.Success).value
        assertEquals("Groceries", repo.stored.getValue(id).title)
    }

    @Test fun rejectsCategoryOfTheWrongKind() = runTest {
        val result = save(expense(categoryId = 1)) // Salary is an income category
        assertTrue(result is ActionResult.Rejected)
        assertTrue(repo.stored.isEmpty())
    }

    @Test fun rejectsUnknownAccount() = runTest {
        assertTrue(save(expense(account = 42)) is ActionResult.Rejected)
        assertTrue(repo.stored.isEmpty())
    }

    @Test fun rejectsTransferToSameAccountAndStoresNothing() = runTest {
        val t = Transaction(
            type = TransactionType.TRANSFER, amountMinor = 1_000, title = "",
            accountId = 1, transferAccountId = 1, occurredAt = 0,
        )
        assertTrue(save(t) is ActionResult.Rejected)
        assertTrue(repo.stored.isEmpty())
    }

    @Test fun savesTransferWithDefaultTitle() = runTest {
        val t = Transaction(
            type = TransactionType.TRANSFER, amountMinor = 300_000, title = "",
            accountId = 1, transferAccountId = 2, occurredAt = 0,
        )
        val id = (save(t) as ActionResult.Success).value
        assertEquals("Transfer", repo.stored.getValue(id).title)
    }

    @Test fun rejectsTransferToUnavailableAccount() = runTest {
        val t = Transaction(
            type = TransactionType.TRANSFER, amountMinor = 1_000, title = "",
            accountId = 1, transferAccountId = 77, occurredAt = 0,
        )
        assertTrue(save(t) is ActionResult.Rejected)
    }

    @Test fun editKeepsCreatedAtAndBumpsUpdatedAt() = runTest {
        val id = (save(expense(title = "Shop")) as ActionResult.Success).value
        val later = Clock.fixed(now.plusSeconds(3_600), ZoneOffset.UTC)
        val editor = SaveTransactionUseCase(repo, FakeCategories(listOf(salary, groceries)), FakeAccounts(1, 2), InrBase, later)

        val original = repo.stored.getValue(id)
        val result = editor(original.copy(amountMinor = 30_000))

        assertTrue(result is ActionResult.Success)
        val edited = repo.stored.getValue(id)
        assertEquals(30_000L, edited.amountMinor)
        assertEquals(original.createdAt, edited.createdAt)
        assertEquals(now.plusSeconds(3_600).toEpochMilli(), edited.updatedAt)
        assertEquals(1, repo.stored.size) // edited in place, not duplicated
    }

    @Test fun cannotChangeTheTypeOfAnExistingTransaction() = runTest {
        val id = (save(expense()) as ActionResult.Success).value
        val asIncome = repo.stored.getValue(id).copy(type = TransactionType.INCOME, categoryId = 1)
        assertTrue(save(asIncome) is ActionResult.Rejected)
        assertEquals(TransactionType.EXPENSE, repo.stored.getValue(id).type)
    }

    @Test fun adjustmentsCannotBeEdited() = runTest {
        val adjustmentId = repo.insert(
            Transaction(
                type = TransactionType.ADJUSTMENT, amountMinor = -500, title = "Balance reconciliation",
                accountId = 1, occurredAt = 0, notes = "Bank fee",
            ),
        )
        val result = save(repo.stored.getValue(adjustmentId).copy(amountMinor = -900))
        assertTrue(result is ActionResult.Rejected)
        assertEquals(-500L, repo.stored.getValue(adjustmentId).amountMinor)
    }

    @Test fun editingAMissingTransactionIsRejected() = runTest {
        assertTrue(save(expense().copy(id = 123)) is ActionResult.Rejected)
    }

    @Test fun duplicateCreatesAnIndependentCopyDatedNow() = runTest {
        val old = expense(title = "Coffee").copy(occurredAt = now.minusSeconds(86_400).toEpochMilli())
        val id = (save(old) as ActionResult.Success).value

        val duplicate = DuplicateTransactionUseCase(repo, save, clock)
        val copyId = (duplicate(id) as ActionResult.Success).value

        assertTrue(copyId != id)
        assertEquals(2, repo.stored.size)
        val copy = repo.stored.getValue(copyId)
        assertEquals("Coffee", copy.title)
        assertEquals(now.toEpochMilli(), copy.occurredAt)
    }

    @Test fun adjustmentsCannotBeDuplicated() = runTest {
        val adjustmentId = repo.insert(
            Transaction(type = TransactionType.ADJUSTMENT, amountMinor = 100, title = "x", accountId = 1, occurredAt = 0),
        )
        val duplicate = DuplicateTransactionUseCase(repo, save, clock)
        assertTrue(duplicate(adjustmentId) is ActionResult.Rejected)
        assertEquals(1, repo.stored.size)
    }
}
