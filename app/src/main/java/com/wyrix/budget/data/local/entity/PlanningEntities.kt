package com.wyrix.budget.data.local.entity

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wyrix.budget.domain.calc.BudgetPeriodType
import com.wyrix.budget.domain.calc.Frequency
import com.wyrix.budget.domain.model.EntryStatus
import com.wyrix.budget.domain.model.GoalPriority
import com.wyrix.budget.domain.model.GoalStatus
import com.wyrix.budget.domain.model.LoanType
import com.wyrix.budget.domain.model.SubscriptionStatus
import com.wyrix.budget.domain.model.TransactionType

@Entity(tableName = "budgets", indices = [Index(value = ["startDate"])])
data class BudgetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val periodType: BudgetPeriodType,
    val startDate: Long,            // epoch day
    val endDate: Long,              // epoch day
    val totalMinor: Long,
    val thresholds: String,         // "50,75,90,100"
    val countOnlyAllocated: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "budget_allocations",
    foreignKeys = [
        ForeignKey(BudgetEntity::class, ["id"], ["budgetId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["budgetId", "categoryId"], unique = true), Index(value = ["categoryId"])],
)
data class BudgetAllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val budgetId: Long,
    val categoryId: Long,
    val amountMinor: Long,
)

@Entity(tableName = "savings_goals")
data class SavingsGoalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val targetMinor: Long,
    val targetDate: Long?,
    val priority: GoalPriority,
    val description: String,
    val status: GoalStatus,
    val createdAt: Long,
)

@Entity(
    tableName = "savings_contributions",
    foreignKeys = [
        ForeignKey(SavingsGoalEntity::class, ["id"], ["goalId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["goalId"]), Index(value = ["accountId"])],
)
data class SavingsContributionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val goalId: Long,
    val amountMinor: Long,
    val accountId: Long?,
    val occurredAt: Long,
    val note: String,
)

@Entity(
    tableName = "bills",
    foreignKeys = [
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["categoryId"]), Index(value = ["accountId"]), Index(value = ["nextDueDate"])],
)
data class BillEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amountMinor: Long,
    val categoryId: Long?,
    val accountId: Long?,
    val startDate: Long,
    val nextDueDate: Long,
    val frequency: Frequency,
    val reminderDays: String,
    val isActive: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "bill_payments",
    foreignKeys = [
        ForeignKey(BillEntity::class, ["id"], ["billId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TransactionEntity::class, ["id"], ["transactionId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["billId", "dueDate"], unique = true), Index(value = ["transactionId"])],
)
data class BillPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val billId: Long,
    val dueDate: Long,
    val paidAt: Long,
    val amountMinor: Long,
    val transactionId: Long?,
)

@Entity(
    tableName = "subscriptions",
    foreignKeys = [
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.SET_NULL),
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["accountId"]), Index(value = ["categoryId"])],
)
data class SubscriptionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val amountMinor: Long,
    val cycle: Frequency,
    val startDate: Long,
    val renewalDate: Long,
    val accountId: Long?,
    val categoryId: Long?,
    val status: SubscriptionStatus,
    val reminderDays: String,
    val createdAt: Long,
)

@Entity(
    tableName = "subscription_payments",
    foreignKeys = [
        ForeignKey(SubscriptionEntity::class, ["id"], ["subscriptionId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TransactionEntity::class, ["id"], ["transactionId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["subscriptionId", "dueDate"], unique = true), Index(value = ["transactionId"])],
)
data class SubscriptionPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val subscriptionId: Long,
    val dueDate: Long,
    val paidAt: Long,
    val amountMinor: Long,
    val transactionId: Long?,
)

@Entity(
    tableName = "loans",
    foreignKeys = [ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.SET_NULL)],
    indices = [Index(value = ["accountId"])],
)
data class LoanEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val lender: String,
    val type: LoanType,
    val principalMinor: Long,
    val outstandingStartMinor: Long,
    val rateBps: Int,
    val emiMinor: Long,
    val frequency: Frequency,
    val startDate: Long,
    val endDate: Long?,
    val nextPaymentDate: Long,
    val accountId: Long?,
    val isActive: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "loan_payments",
    foreignKeys = [
        ForeignKey(LoanEntity::class, ["id"], ["loanId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TransactionEntity::class, ["id"], ["transactionId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["loanId"]), Index(value = ["transactionId"])],
)
data class LoanPaymentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val loanId: Long,
    val paidDate: Long,
    val amountMinor: Long,
    val principalPartMinor: Long,
    val interestPartMinor: Long,
    val transactionId: Long?,
)

@Entity(
    tableName = "recurring_rules",
    foreignKeys = [
        ForeignKey(CategoryEntity::class, ["id"], ["categoryId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(AccountEntity::class, ["id"], ["accountId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["categoryId"]), Index(value = ["accountId"])],
)
data class RecurringRuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val type: TransactionType,
    val amountMinor: Long,
    val categoryId: Long,
    val accountId: Long,
    val frequency: Frequency,
    val customDays: Int,
    val startDate: Long,
    val nextDate: Long,
    val endDate: Long?,
    val isActive: Boolean,
    val createdAt: Long,
)

@Entity(
    tableName = "recurring_entries",
    foreignKeys = [
        ForeignKey(RecurringRuleEntity::class, ["id"], ["ruleId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(TransactionEntity::class, ["id"], ["transactionId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index(value = ["ruleId", "dueDate"], unique = true), Index(value = ["transactionId"])],
)
data class RecurringEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val ruleId: Long,
    val dueDate: Long,
    val status: EntryStatus,
    val transactionId: Long?,
    val handledAt: Long,
)

@Entity(
    tableName = "attachments",
    foreignKeys = [ForeignKey(TransactionEntity::class, ["id"], ["transactionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["transactionId"])],
)
data class AttachmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val transactionId: Long,
    val filePath: String,
    val mimeType: String,
    val createdAt: Long,
)

@Entity(tableName = "exchange_rates", indices = [Index(value = ["currencyCode"], unique = true)])
data class ExchangeRateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val currencyCode: String,
    val rateToBase: String,
    val asOf: Long,
    val source: String,
)

// ---- query result rows ----------------------------------------------------------------------
data class GoalRow(@Embedded val goal: SavingsGoalEntity, val savedMinor: Long)
data class LoanRow(@Embedded val loan: LoanEntity, val outstandingMinor: Long)
data class AllocationRow(val categoryId: Long, val name: String, val amountMinor: Long)
data class AllocationWithBudgetRow(val budgetId: Long, val categoryId: Long, val name: String, val amountMinor: Long)
data class CategoryAmountRow(val categoryId: Long, val name: String, val amountMinor: Long)
data class LabeledAmountRow(val label: String, val amountMinor: Long)
data class MonthTotalsRow(val month: String, val incomeMinor: Long, val expenseMinor: Long)
