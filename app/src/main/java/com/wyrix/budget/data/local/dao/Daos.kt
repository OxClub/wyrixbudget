package com.wyrix.budget.data.local.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.wyrix.budget.data.local.entity.AccountEntity
import com.wyrix.budget.data.local.entity.AccountWithBalanceRow
import com.wyrix.budget.data.local.entity.CategoryEntity
import com.wyrix.budget.data.local.entity.PeriodTotalsRow
import com.wyrix.budget.data.local.entity.TransactionEntity
import com.wyrix.budget.data.local.entity.TransactionRow
import com.wyrix.budget.domain.model.CategoryKind
import kotlinx.coroutines.flow.Flow

/**
 * The single source of truth for balances. A balance is never stored; it is opening balance
 * plus income, minus expenses, plus/minus transfers and signed adjustments. Editing or deleting
 * a transaction therefore always yields a correct balance with no separate bookkeeping.
 */
internal const val ACCOUNT_BALANCE_SELECT = """
SELECT a.*, a.openingBalanceMinor + COALESCE((
    SELECT SUM(CASE
        WHEN t.type = 'TRANSFER' AND t.transferAccountId = a.id THEN t.amountMinor
        WHEN t.accountId = a.id AND t.type = 'INCOME' THEN t.amountMinor
        WHEN t.accountId = a.id AND t.type = 'ADJUSTMENT' THEN t.amountMinor
        WHEN t.accountId = a.id AND t.type IN ('EXPENSE', 'TRANSFER') THEN -t.amountMinor
        ELSE 0
    END)
    FROM transactions t
    WHERE t.accountId = a.id OR t.transferAccountId = a.id
), 0) AS balanceMinor
FROM accounts a
"""

internal const val TRANSACTION_ROW_SELECT = """
SELECT t.*, c.name AS categoryName, c.colorArgb AS categoryColorArgb,
       a.name AS accountName, ta.name AS transferAccountName
FROM transactions t
INNER JOIN accounts a ON a.id = t.accountId
LEFT JOIN accounts ta ON ta.id = t.transferAccountId
LEFT JOIN categories c ON c.id = t.categoryId
"""

@Dao
interface AccountDao {
    @Insert
    suspend fun insert(account: AccountEntity): Long

    @Query("$ACCOUNT_BALANCE_SELECT WHERE a.isArchived = 0 ORDER BY a.sortOrder, a.id")
    fun observeWithBalance(): Flow<List<AccountWithBalanceRow>>

    @Query("$ACCOUNT_BALANCE_SELECT WHERE a.id = :id")
    fun observeOneWithBalance(id: Long): Flow<AccountWithBalanceRow?>

    @Query("$ACCOUNT_BALANCE_SELECT WHERE a.id = :id AND a.isArchived = 0")
    suspend fun getActiveWithBalance(id: Long): AccountWithBalanceRow?

    @Query("UPDATE accounts SET isArchived = 1 WHERE id = :id")
    suspend fun archive(id: Long)
}

@Dao
interface CategoryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<CategoryEntity>): List<Long>

    @Insert
    suspend fun insert(category: CategoryEntity): Long

    @Query("SELECT * FROM categories WHERE kind = :kind ORDER BY isDefault DESC, id")
    fun observeByKind(kind: CategoryKind): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY kind, name")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE kind = :kind AND name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(kind: CategoryKind, name: String): CategoryEntity?

    @Query("UPDATE categories SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String): Int

    @Query("DELETE FROM categories WHERE id = :id")
    suspend fun delete(id: Long): Int

    @Query("SELECT COUNT(*) FROM categories WHERE kind = :kind")
    suspend fun count(kind: CategoryKind): Int

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun countAll(): Int
}

@Dao
interface TransactionDao {
    @Insert
    suspend fun insert(transaction: TransactionEntity): Long

    /** @return number of rows updated (0 if the id does not exist). */
    @Update
    suspend fun update(transaction: TransactionEntity): Int

    /** @return number of rows deleted (0 if the id does not exist). */
    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: Long): TransactionEntity?

    @Query(
        """
        $TRANSACTION_ROW_SELECT
        WHERE (:type IS NULL OR t.type = :type)
          AND (:accountId IS NULL OR t.accountId = :accountId OR t.transferAccountId = :accountId)
          AND (:categoryId IS NULL OR t.categoryId = :categoryId)
          AND (:fromMillis IS NULL OR t.occurredAt >= :fromMillis)
          AND (:toMillis IS NULL OR t.occurredAt < :toMillis)
          AND (:query = ''
               OR instr(lower(t.title), lower(:query)) > 0
               OR instr(lower(t.merchant), lower(:query)) > 0
               OR instr(lower(t.notes), lower(:query)) > 0
               OR instr(lower(c.name), lower(:query)) > 0
               OR instr(lower(a.name), lower(:query)) > 0
               OR (:amountMinor IS NOT NULL AND t.amountMinor = :amountMinor))
        ORDER BY
            CASE WHEN :ascending = 1 THEN t.occurredAt END ASC,
            CASE WHEN :ascending = 0 THEN t.occurredAt END DESC,
            CASE WHEN :ascending = 1 THEN t.id END ASC,
            CASE WHEN :ascending = 0 THEN t.id END DESC
        """,
    )
    fun pagingSource(
        query: String,
        amountMinor: Long?,
        type: String?,
        accountId: Long?,
        categoryId: Long?,
        fromMillis: Long?,
        toMillis: Long?,
        ascending: Int,
    ): PagingSource<Int, TransactionRow>

    @Query("$TRANSACTION_ROW_SELECT WHERE t.occurredAt >= :fromMillis AND t.occurredAt < :toMillis ORDER BY t.occurredAt ASC, t.id ASC")
    suspend fun listInRange(fromMillis: Long, toMillis: Long): List<TransactionRow>

    @Query("$TRANSACTION_ROW_SELECT ORDER BY t.occurredAt DESC, t.id DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TransactionRow>>

    /** Income and expense only; transfers and adjustments are deliberately excluded. */
    @Query(
        """
        SELECT
            COALESCE(SUM(CASE WHEN type = 'INCOME' THEN baseAmountMinor ELSE 0 END), 0) AS incomeMinor,
            COALESCE(SUM(CASE WHEN type = 'EXPENSE' THEN baseAmountMinor ELSE 0 END), 0) AS expenseMinor
        FROM transactions
        WHERE occurredAt >= :fromMillis AND occurredAt < :toMillis
        """,
    )
    fun observeTotals(fromMillis: Long, toMillis: Long): Flow<PeriodTotalsRow>
}
