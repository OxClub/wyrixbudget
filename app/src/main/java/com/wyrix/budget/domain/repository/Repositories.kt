package com.wyrix.budget.domain.repository

import androidx.paging.PagingData
import com.wyrix.budget.core.time.TimeRange
import com.wyrix.budget.domain.model.Account
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.Category
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.PeriodTotals
import com.wyrix.budget.domain.model.Transaction
import com.wyrix.budget.domain.model.TransactionFilter
import com.wyrix.budget.domain.model.TransactionListItem
import kotlinx.coroutines.flow.Flow

/** Thrown by a repository when a name collides with an existing record. */
class DuplicateNameException : Exception("Name already in use")

interface AccountRepository {
    fun observeAccounts(): Flow<List<AccountWithBalance>>
    fun observeAccount(id: Long): Flow<AccountWithBalance?>
    suspend fun getActive(id: Long): AccountWithBalance?

    /** @throws DuplicateNameException if an account with the same name exists. */
    suspend fun create(account: Account): Long
    suspend fun archive(id: Long)
}

interface CategoryRepository {
    fun observeByKind(kind: CategoryKind): Flow<List<Category>>
    fun observeAll(): Flow<List<Category>>
    suspend fun getById(id: Long): Category?
    suspend fun count(kind: CategoryKind): Int
    suspend fun findByName(kind: CategoryKind, name: String): Category?

    /** @throws DuplicateNameException if the new name collides. */
    suspend fun rename(id: Long, name: String)

    /** @return false if the category is still used by transactions, budgets or recurring items. */
    suspend fun deleteIfUnused(id: Long): Boolean

    /** @throws DuplicateNameException if a category of the same kind and name exists. */
    suspend fun create(category: Category): Long
}

interface TransactionRepository {
    fun paged(filter: TransactionFilter): Flow<PagingData<TransactionListItem>>
    fun observeRecent(limit: Int): Flow<List<TransactionListItem>>
    fun observeTotals(range: TimeRange): Flow<PeriodTotals>
    suspend fun listInRange(range: TimeRange): List<TransactionListItem>
    suspend fun getById(id: Long): Transaction?
    suspend fun insert(transaction: Transaction): Long

    /** @return false if no transaction with that id exists. */
    suspend fun update(transaction: Transaction): Boolean

    /** @return false if no transaction with that id exists. */
    suspend fun delete(id: Long): Boolean

    /**
     * Atomically compares the calculated balance with [actualBalanceMinor] and, if different,
     * records a signed ADJUSTMENT transaction carrying [reason].
     * @return the difference (actual minus calculated); 0 means nothing was recorded.
     */
    suspend fun reconcile(accountId: Long, actualBalanceMinor: Long, reason: String, nowMillis: Long): Long
}

/** Supplies the user's base (reporting) currency code. */
interface BaseCurrencyProvider {
    suspend fun code(): String
}
