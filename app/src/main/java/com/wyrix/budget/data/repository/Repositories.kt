package com.wyrix.budget.data.repository

import android.database.sqlite.SQLiteConstraintException
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.room.withTransaction
import com.wyrix.budget.core.money.MoneyParser
import com.wyrix.budget.core.time.TimeRange
import com.wyrix.budget.data.local.AppDatabase
import com.wyrix.budget.data.local.dao.AccountDao
import com.wyrix.budget.data.local.dao.CategoryDao
import com.wyrix.budget.data.local.dao.TransactionDao
import com.wyrix.budget.data.local.entity.TransactionEntity
import com.wyrix.budget.domain.model.Account
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.Category
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.PaymentMethod
import com.wyrix.budget.domain.model.PeriodTotals
import com.wyrix.budget.domain.model.Transaction
import com.wyrix.budget.domain.model.TransactionFilter
import com.wyrix.budget.domain.model.TransactionListItem
import com.wyrix.budget.domain.model.TransactionType
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.CategoryRepository
import com.wyrix.budget.domain.repository.DuplicateNameException
import com.wyrix.budget.domain.repository.TransactionRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class AccountRepositoryImpl @Inject constructor(
    private val dao: AccountDao,
) : AccountRepository {

    override fun observeAccounts(): Flow<List<AccountWithBalance>> =
        dao.observeWithBalance().map { rows -> rows.map { it.toDomain() } }

    override fun observeAccount(id: Long): Flow<AccountWithBalance?> =
        dao.observeOneWithBalance(id).map { it?.toDomain() }

    override suspend fun getActive(id: Long): AccountWithBalance? =
        dao.getActiveWithBalance(id)?.toDomain()

    override suspend fun create(account: Account): Long =
        try {
            dao.insert(account.toEntity())
        } catch (e: SQLiteConstraintException) {
            throw DuplicateNameException()
        }

    override suspend fun archive(id: Long) = dao.archive(id)
}

class CategoryRepositoryImpl @Inject constructor(
    private val dao: CategoryDao,
) : CategoryRepository {

    override fun observeByKind(kind: CategoryKind): Flow<List<Category>> =
        dao.observeByKind(kind).map { rows -> rows.map { it.toDomain() } }

    override fun observeAll(): Flow<List<Category>> =
        dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun getById(id: Long): Category? = dao.getById(id)?.toDomain()

    override suspend fun count(kind: CategoryKind): Int = dao.count(kind)

    override suspend fun findByName(kind: CategoryKind, name: String): Category? = dao.findByName(kind, name)?.toDomain()

    override suspend fun rename(id: Long, name: String) {
        try { dao.rename(id, name) } catch (e: SQLiteConstraintException) { throw DuplicateNameException() }
    }

    override suspend fun deleteIfUnused(id: Long): Boolean =
        try { dao.delete(id) > 0 } catch (e: SQLiteConstraintException) { false }

    override suspend fun create(category: Category): Long =
        try {
            dao.insert(category.toEntity())
        } catch (e: SQLiteConstraintException) {
            throw DuplicateNameException()
        }
}

class TransactionRepositoryImpl @Inject constructor(
    private val db: AppDatabase,
    private val dao: TransactionDao,
    private val accountDao: AccountDao,
) : TransactionRepository {

    override fun paged(filter: TransactionFilter): Flow<PagingData<TransactionListItem>> {
        val query = filter.query.trim()
        val amountMinor = if (query.isEmpty()) null else MoneyParser.parseOrNull(query)
        return Pager(PagingConfig(pageSize = PAGE_SIZE, enablePlaceholders = false)) {
            dao.pagingSource(
                query = query,
                amountMinor = amountMinor,
                type = filter.type?.name,
                accountId = filter.accountId,
                categoryId = filter.categoryId,
                fromMillis = filter.range?.fromMillis,
                toMillis = filter.range?.toMillis,
                ascending = if (filter.ascending) 1 else 0,
            )
        }.flow.map { pagingData -> pagingData.map { it.toDomain() } }
    }

    override fun observeRecent(limit: Int): Flow<List<TransactionListItem>> =
        dao.observeRecent(limit).map { rows -> rows.map { it.toDomain() } }

    override fun observeTotals(range: TimeRange): Flow<PeriodTotals> =
        dao.observeTotals(range.fromMillis, range.toMillis)
            .map { PeriodTotals(incomeMinor = it.incomeMinor, expenseMinor = it.expenseMinor) }

    override suspend fun listInRange(range: TimeRange): List<TransactionListItem> =
        dao.listInRange(range.fromMillis, range.toMillis).map { it.toDomain() }

    override suspend fun getById(id: Long): Transaction? = dao.getById(id)?.toDomain()

    override suspend fun insert(transaction: Transaction): Long =
        dao.insert(transaction.copy(id = 0).toEntity())

    override suspend fun update(transaction: Transaction): Boolean =
        dao.update(transaction.toEntity()) > 0

    override suspend fun delete(id: Long): Boolean = dao.deleteById(id) > 0

    override suspend fun reconcile(
        accountId: Long,
        actualBalanceMinor: Long,
        reason: String,
        nowMillis: Long,
    ): Long = db.withTransaction {
        val row = accountDao.getActiveWithBalance(accountId)
            ?: throw IllegalStateException("Account $accountId not found")
        val difference = actualBalanceMinor - row.balanceMinor
        if (difference != 0L) {
            dao.insert(
                TransactionEntity(
                    type = TransactionType.ADJUSTMENT,
                    amountMinor = difference,
                    title = "Balance reconciliation",
                    categoryId = null,
                    accountId = accountId,
                    transferAccountId = null,
                    occurredAt = nowMillis,
                    merchant = "",
                    notes = reason,
                    createdAt = nowMillis,
                    updatedAt = nowMillis,
                    currencyCode = row.account.currencyCode,
                    baseAmountMinor = difference,
                    exchangeRate = null,
                    rateDate = null,
                    paymentMethod = PaymentMethod.OTHER,
                    gstTagged = false,
                    gstAmountMinor = null,
                ),
            )
        }
        difference
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
