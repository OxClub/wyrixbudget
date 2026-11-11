package com.wyrix.budget

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyrix.budget.core.time.TimeRange
import com.wyrix.budget.data.local.AppDatabase
import com.wyrix.budget.data.local.entity.AccountEntity
import com.wyrix.budget.data.local.entity.CategoryEntity
import com.wyrix.budget.data.local.entity.TransactionEntity
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.PaymentMethod
import com.wyrix.budget.domain.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

/** Large-data checks: correctness of totals plus generous time limits so regressions in query plans are caught. */
@RunWith(AndroidJUnit4::class)
class DatabasePerformanceTest {
    private lateinit var db: AppDatabase
    private val count = 10_000

    @Before fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()
        db.backupDao().putAccounts(listOf(AccountEntity(1, "Bank", AccountType.BANK, "INR", 0, "", 0, false, 0, 0)))
        db.backupDao().putCategories(listOf(CategoryEntity(1, "Food", CategoryKind.EXPENSE, null, 0, true), CategoryEntity(2, "Salary", CategoryKind.INCOME, null, 0, true)))
        val rows = (1..count).map { i ->
            val income = i % 10 == 0
            TransactionEntity(
                i.toLong(), if (income) TransactionType.INCOME else TransactionType.EXPENSE, 10_000L, "Item $i", if (income) 2 else 1, 1, null,
                1_000_000L + i, "Merchant ${i % 50}", "", 0, 0, "INR", 10_000L, null, null, PaymentMethod.UPI, false, null,
            )
        }
        db.backupDao().putTransactions(rows)
    }

    @After fun tearDown() = db.close()

    @Test fun balancesAndTotalsAreCorrectAndFastOnTenThousandRows() = runBlocking {
        val incomeCount = count / 10; val expenseCount = count - incomeCount
        var balance = 0L
        val balanceMs = measureTimeMillis { balance = db.accountDao().getActiveWithBalance(1)!!.balanceMinor }
        assertEquals((incomeCount - expenseCount) * 10_000L, balance)
        assertTrue("balance query took ${balanceMs}ms", balanceMs < 2_000)

        val totalsMs = measureTimeMillis {
            val t = db.transactionDao().observeTotals(0, Long.MAX_VALUE).first()
            assertEquals(incomeCount * 10_000L, t.incomeMinor); assertEquals(expenseCount * 10_000L, t.expenseMinor)
        }
        assertTrue("totals took ${totalsMs}ms", totalsMs < 2_000)

        val catMs = measureTimeMillis { assertEquals(1, db.analyticsDao().byCategory("EXPENSE", 0, Long.MAX_VALUE).size) }
        assertTrue("category analytics took ${catMs}ms", catMs < 2_000)

        val listMs = measureTimeMillis { assertEquals(count, db.transactionDao().listInRange(0, Long.MAX_VALUE).size) }
        assertTrue("full list took ${listMs}ms", listMs < 5_000)
    }

    @Test fun databaseIntegrityHoldsAfterBulkInsert() {
        db.openHelper.writableDatabase.query("PRAGMA integrity_check").use { c -> c.moveToFirst(); assertEquals("ok", c.getString(0)) }
        db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
    }

    @Test fun merchantAnalyticsGroupsCaseInsensitively() = runBlocking {
        val top = db.analyticsDao().byMerchant(0, Long.MAX_VALUE)
        assertTrue(top.size <= 10)
        assertTrue(top.zipWithNext().all { (a, b) -> a.amountMinor >= b.amountMinor })
    }
}
