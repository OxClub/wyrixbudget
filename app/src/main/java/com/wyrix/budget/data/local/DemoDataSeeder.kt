package com.wyrix.budget.data.local

import androidx.room.withTransaction
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.data.local.dao.*
import com.wyrix.budget.data.local.entity.*
import com.wyrix.budget.domain.calc.BudgetPeriodType
import com.wyrix.budget.domain.calc.Frequency
import com.wyrix.budget.domain.model.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

/** Fills the separate demo database with clearly labelled sample data. Only ever runs on the demo file. */
class DemoDataSeeder @Inject constructor(
    private val db: AppDatabase,
    private val accounts: AccountDao,
    private val categories: CategoryDao,
    private val tx: TransactionDao,
    private val budgets: BudgetDao,
    private val goals: GoalDao,
    private val bills: BillDao,
    private val clock: Clock,
) {
    suspend fun seedIfEmpty() {
        db.withTransaction {
            val existing = db.backupDao().accounts()
            if (existing.isNotEmpty()) return@withTransaction
            val now = clock.millis()
            val bank = accounts.insert(AccountEntity(name = "Demo Bank", type = AccountType.SAVINGS, currencyCode = "INR", openingBalanceMinor = 8_000_000, description = "Sample data", colorArgb = ColorPalette.colors[1], createdAt = now))
            val cash = accounts.insert(AccountEntity(name = "Demo Cash", type = AccountType.CASH, currencyCode = "INR", openingBalanceMinor = 500_000, description = "Sample data", colorArgb = ColorPalette.colors[2], createdAt = now))
            val all = db.backupDao().categories()
            fun cat(name: String) = all.first { it.name == name }.id
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val pending = ArrayList<TransactionEntity>()
            fun add(type: TransactionType, minor: Long, title: String, categoryName: String?, account: Long, daysAgo: Long, merchant: String = "") {
                val at = today.minusDays(daysAgo).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
                pending += (TransactionEntity(type = type, amountMinor = minor, title = title, categoryId = categoryName?.let { cat(it) }, accountId = account, transferAccountId = null, occurredAt = at, merchant = merchant, notes = "Demo", createdAt = now, updatedAt = now, currencyCode = "INR", baseAmountMinor = minor, exchangeRate = null, rateDate = null, paymentMethod = PaymentMethod.UPI, gstTagged = false, gstAmountMinor = null))
            }
            for (m in 0L..2L) {
                val base = m * 30
                add(TransactionType.INCOME, 6_000_000, "Salary", "Salary", bank, base + 20)
                add(TransactionType.EXPENSE, 1_500_000, "Rent", "Rent", bank, base + 18)
                add(TransactionType.EXPENSE, 480_000 + m * 20_000, "Groceries", "Groceries", bank, base + 12, "FreshMart")
                add(TransactionType.EXPENSE, 120_000, "Electricity bill", "Electricity", bank, base + 9)
                add(TransactionType.EXPENSE, 90_000 + m * 15_000, "Dinner out", "Restaurants", cash, base + 6, "Spice Hub")
                add(TransactionType.EXPENSE, 210_000, "Shopping", "Shopping", bank, base + 3, "Mall")
                add(TransactionType.EXPENSE, 60_000, "Metro card", "Transportation", cash, base + 1)
            }
            pending.forEach { tx.insert(it) }
            val (start, end) = com.wyrix.budget.domain.calc.BudgetCalculator.periodFor(BudgetPeriodType.MONTHLY, today)
            val budgetId = budgets.insert(BudgetEntity(name = "Demo monthly budget", periodType = BudgetPeriodType.MONTHLY, startDate = start.toEpochDay(), endDate = end.toEpochDay(), totalMinor = 4_000_000, thresholds = "50,75,90,100", countOnlyAllocated = false, createdAt = now))
            budgets.insertAllocations(listOf(
                BudgetAllocationEntity(budgetId = budgetId, categoryId = cat("Groceries"), amountMinor = 800_000),
                BudgetAllocationEntity(budgetId = budgetId, categoryId = cat("Restaurants"), amountMinor = 300_000),
                BudgetAllocationEntity(budgetId = budgetId, categoryId = cat("Shopping"), amountMinor = 400_000),
            ))
            val goalId = goals.insert(SavingsGoalEntity(name = "Demo emergency fund", targetMinor = 9_000_000, targetDate = today.plusMonths(10).toEpochDay(), priority = GoalPriority.HIGH, description = "Sample goal", status = GoalStatus.ACTIVE, createdAt = now))
            goals.insertContribution(SavingsContributionEntity(goalId = goalId, amountMinor = 3_500_000, accountId = bank, occurredAt = now, note = "Sample"))
            bills.insert(BillEntity(name = "Demo internet bill", amountMinor = 79_900, categoryId = cat("Internet"), accountId = bank, startDate = today.plusDays(5).toEpochDay(), nextDueDate = today.plusDays(5).toEpochDay(), frequency = Frequency.MONTHLY, reminderDays = "3,1,0", isActive = true, createdAt = now))
        }
    }
}
