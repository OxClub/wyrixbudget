package com.wyrix.budget

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.wyrix.budget.data.local.AppDatabase
import com.wyrix.budget.data.local.MIGRATION_1_2
import com.wyrix.budget.data.local.entity.BudgetEntity
import com.wyrix.budget.domain.calc.BudgetPeriodType
import com.wyrix.budget.domain.model.PaymentMethod
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Builds a genuine version-1 database file (the Phase 1-2 schema) with data in it, then opens it with the
 * current Room database so MIGRATION_1_2 and Room's schema validation both run against real data.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val name = "migration-test.db"

    @Before fun clean() { context.deleteDatabase(name) }
    @After fun cleanup() { context.deleteDatabase(name) }

    private val v1 = listOf(
        "CREATE TABLE IF NOT EXISTS `accounts` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `type` TEXT NOT NULL, `currencyCode` TEXT NOT NULL, `openingBalanceMinor` INTEGER NOT NULL, `description` TEXT NOT NULL, `colorArgb` INTEGER NOT NULL, `isArchived` INTEGER NOT NULL, `sortOrder` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_accounts_name` ON `accounts` (`name`)",
        "CREATE INDEX IF NOT EXISTS `index_accounts_isArchived` ON `accounts` (`isArchived`)",
        "CREATE TABLE IF NOT EXISTS `categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL COLLATE NOCASE, `kind` TEXT NOT NULL, `expenseGroup` TEXT, `colorArgb` INTEGER NOT NULL, `isDefault` INTEGER NOT NULL)",
        "CREATE UNIQUE INDEX IF NOT EXISTS `index_categories_kind_name` ON `categories` (`kind`, `name`)",
        "CREATE TABLE IF NOT EXISTS `transactions` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `type` TEXT NOT NULL, `amountMinor` INTEGER NOT NULL, `title` TEXT NOT NULL, `categoryId` INTEGER, `accountId` INTEGER NOT NULL, `transferAccountId` INTEGER, `occurredAt` INTEGER NOT NULL, `merchant` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `updatedAt` INTEGER NOT NULL, FOREIGN KEY(`accountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`transferAccountId`) REFERENCES `accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`categoryId`) REFERENCES `categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
        "CREATE INDEX IF NOT EXISTS `index_transactions_accountId` ON `transactions` (`accountId`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_transferAccountId` ON `transactions` (`transferAccountId`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_categoryId` ON `transactions` (`categoryId`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_occurredAt` ON `transactions` (`occurredAt`)",
        "CREATE INDEX IF NOT EXISTS `index_transactions_type` ON `transactions` (`type`)",
    )

    private fun createVersion1Database() {
        val helper = object : SQLiteOpenHelper(context, name, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                v1.forEach { db.execSQL(it) }
                db.execSQL("INSERT INTO accounts VALUES (1, 'Bank', 'BANK', 'INR', 5000000, '', 0, 0, 0, 1)")
                db.execSQL("INSERT INTO categories VALUES (1, 'Groceries', 'EXPENSE', 'ESSENTIAL', 0, 1)")
                db.execSQL("INSERT INTO transactions VALUES (1, 'EXPENSE', 12345, 'Weekly shop', 1, 1, NULL, 1000, 'FreshMart', 'notes', 1, 1)")
                db.execSQL("INSERT INTO transactions VALUES (2, 'INCOME', 900000, 'Salary', NULL, 1, NULL, 2000, '', '', 1, 1)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        helper.writableDatabase.close()
        helper.close()
    }

    private fun openMigrated(): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, name).addMigrations(MIGRATION_1_2).build()

    @Test fun existingDataSurvivesTheMigration() = runBlocking {
        createVersion1Database()
        val db = openMigrated()
        try {
            db.openHelper.writableDatabase // runs the migration and Room's schema validation
            val t = db.transactionDao().getById(1)!!
            assertEquals("Weekly shop", t.title)
            assertEquals(12_345L, t.amountMinor)
            assertEquals("INR", t.currencyCode)
            assertEquals(12_345L, t.baseAmountMinor)          // base amount back-filled from the original amount
            assertEquals(PaymentMethod.OTHER, t.paymentMethod)
            assertEquals(false, t.gstTagged)
            // balances still derive correctly from the migrated rows: 50,000.00 - 123.45 + 9,000.00
            assertEquals(5_000_000L - 12_345L + 900_000L, db.accountDao().getActiveWithBalance(1)!!.balanceMinor)
        } finally { db.close() }
    }

    @Test fun newTablesWorkAfterMigrationAndForeignKeysStayIntact() = runBlocking {
        createVersion1Database()
        val db = openMigrated()
        try {
            val id = db.budgetDao().insert(BudgetEntity(name = "Nov", periodType = BudgetPeriodType.MONTHLY, startDate = 1, endDate = 30, totalMinor = 100, thresholds = "50,100", countOnlyAllocated = false, createdAt = 1))
            assertEquals("Nov", db.budgetDao().get(id)!!.name)
            db.openHelper.writableDatabase.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally { db.close() }
    }
}
