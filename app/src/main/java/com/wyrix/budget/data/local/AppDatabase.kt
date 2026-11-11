package com.wyrix.budget.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.wyrix.budget.data.local.dao.*
import com.wyrix.budget.data.local.entity.*

/**
 * Version 2. The schema is exported to app/schemas on every build. Every schema change must bump the
 * version and ship a Migration (see Migrations.kt); destructive migration is intentionally never enabled.
 */
@Database(
    entities = [
        AccountEntity::class, CategoryEntity::class, TransactionEntity::class,
        BudgetEntity::class, BudgetAllocationEntity::class,
        SavingsGoalEntity::class, SavingsContributionEntity::class,
        BillEntity::class, BillPaymentEntity::class,
        SubscriptionEntity::class, SubscriptionPaymentEntity::class,
        LoanEntity::class, LoanPaymentEntity::class,
        RecurringRuleEntity::class, RecurringEntryEntity::class,
        AttachmentEntity::class, ExchangeRateEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun categoryDao(): CategoryDao
    abstract fun transactionDao(): TransactionDao
    abstract fun budgetDao(): BudgetDao
    abstract fun goalDao(): GoalDao
    abstract fun billDao(): BillDao
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun loanDao(): LoanDao
    abstract fun recurringDao(): RecurringDao
    abstract fun attachmentDao(): AttachmentDao
    abstract fun exchangeRateDao(): ExchangeRateDao
    abstract fun analyticsDao(): AnalyticsDao
    abstract fun backupDao(): BackupDao
}
