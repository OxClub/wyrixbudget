package com.wyrix.budget.di

import android.content.Context
import androidx.room.Room
import com.wyrix.budget.data.local.AppDatabase
import com.wyrix.budget.data.local.MIGRATION_1_2
import com.wyrix.budget.data.prefs.DemoMode
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.data.local.dao.*
import com.wyrix.budget.data.repository.*
import com.wyrix.budget.domain.repository.*
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.time.Clock
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        // No fallbackToDestructiveMigration: user financial data must never be wiped silently.
        Room.databaseBuilder(context, AppDatabase::class.java, DemoMode.dbName(context))
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun provideAccountDao(db: AppDatabase): AccountDao = db.accountDao()
    @Provides fun provideCategoryDao(db: AppDatabase): CategoryDao = db.categoryDao()
    @Provides fun provideTransactionDao(db: AppDatabase): TransactionDao = db.transactionDao()
    @Provides fun provideBudgetDao(db: AppDatabase): BudgetDao = db.budgetDao()
    @Provides fun provideGoalDao(db: AppDatabase): GoalDao = db.goalDao()
    @Provides fun provideBillDao(db: AppDatabase): BillDao = db.billDao()
    @Provides fun provideSubscriptionDao(db: AppDatabase): SubscriptionDao = db.subscriptionDao()
    @Provides fun provideLoanDao(db: AppDatabase): LoanDao = db.loanDao()
    @Provides fun provideRecurringDao(db: AppDatabase): RecurringDao = db.recurringDao()
    @Provides fun provideAttachmentDao(db: AppDatabase): AttachmentDao = db.attachmentDao()
    @Provides fun provideExchangeRateDao(db: AppDatabase): ExchangeRateDao = db.exchangeRateDao()
    @Provides fun provideAnalyticsDao(db: AppDatabase): AnalyticsDao = db.analyticsDao()
    @Provides fun provideBackupDao(db: AppDatabase): BackupDao = db.backupDao()

    @Provides
    @Singleton
    fun provideClock(): Clock = Clock.systemUTC()
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds @Singleton
    abstract fun bindAccounts(impl: AccountRepositoryImpl): AccountRepository

    @Binds @Singleton
    abstract fun bindCategories(impl: CategoryRepositoryImpl): CategoryRepository

    @Binds @Singleton
    abstract fun bindTransactions(impl: TransactionRepositoryImpl): TransactionRepository

    @Binds @Singleton abstract fun bindBudgets(impl: BudgetRepositoryImpl): BudgetRepository
    @Binds @Singleton abstract fun bindGoals(impl: GoalRepositoryImpl): GoalRepository
    @Binds @Singleton abstract fun bindBills(impl: BillRepositoryImpl): BillRepository
    @Binds @Singleton abstract fun bindSubscriptions(impl: SubscriptionRepositoryImpl): SubscriptionRepository
    @Binds @Singleton abstract fun bindLoans(impl: LoanRepositoryImpl): LoanRepository
    @Binds @Singleton abstract fun bindRecurring(impl: RecurringRepositoryImpl): RecurringRepository
    @Binds @Singleton abstract fun bindAttachments(impl: AttachmentRepositoryImpl): AttachmentRepository
    @Binds @Singleton abstract fun bindRates(impl: ExchangeRateRepositoryImpl): ExchangeRateRepository
    @Binds @Singleton abstract fun bindAnalytics(impl: AnalyticsRepositoryImpl): AnalyticsRepository
    @Binds @Singleton abstract fun bindBaseCurrency(impl: UserPreferencesRepository): BaseCurrencyProvider
}
