package com.wyrix.budget.domain.model

import com.wyrix.budget.domain.calc.BudgetPeriodType
import com.wyrix.budget.domain.calc.Frequency
import java.time.LocalDate

enum class PaymentMethod(val label: String) {
    CASH("Cash"), UPI("UPI"), DEBIT_CARD("Debit card"), CREDIT_CARD("Credit card"),
    BANK_TRANSFER("Bank transfer"), NEFT("NEFT"), RTGS("RTGS"), IMPS("IMPS"), OTHER("Other"),
}

// ---- budgets ------------------------------------------------------------------------------
data class Budget(
    val id: Long = 0,
    val name: String,
    val periodType: BudgetPeriodType,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val totalMinor: Long,
    val thresholds: List<Int>,
    val countOnlyAllocated: Boolean,
    val createdAt: Long = 0,
)

// ---- savings goals ------------------------------------------------------------------------
enum class GoalPriority(val label: String) { LOW("Low"), MEDIUM("Medium"), HIGH("High") }
enum class GoalStatus(val label: String) { ACTIVE("Active"), PAUSED("Paused"), COMPLETED("Completed") }

data class SavingsGoal(
    val id: Long = 0,
    val name: String,
    val targetMinor: Long,
    val targetDate: LocalDate?,
    val priority: GoalPriority,
    val description: String,
    val status: GoalStatus,
    val createdAt: Long = 0,
)

data class GoalWithSaved(val goal: SavingsGoal, val savedMinor: Long)

/** Positive = money added to the goal, negative = withdrawn. Never counted as income or expense. */
data class GoalContribution(
    val id: Long = 0,
    val goalId: Long,
    val amountMinor: Long,
    val accountId: Long?,
    val occurredAt: Long,
    val note: String,
)

// ---- bills --------------------------------------------------------------------------------
enum class BillStatus(val label: String) { PAID("Paid"), UPCOMING("Upcoming"), DUE_SOON("Due soon"), OVERDUE("Overdue") }

data class Bill(
    val id: Long = 0,
    val name: String,
    val amountMinor: Long,
    val categoryId: Long?,
    val accountId: Long?,
    val startDate: LocalDate,
    val nextDueDate: LocalDate,
    val frequency: Frequency,
    val reminderDays: List<Int>,
    val isActive: Boolean,
    val createdAt: Long = 0,
) {
    fun status(today: LocalDate, dueSoonDays: Int = 7): BillStatus = when {
        !isActive -> BillStatus.PAID
        nextDueDate.isBefore(today) -> BillStatus.OVERDUE
        !nextDueDate.isAfter(today.plusDays(dueSoonDays.toLong())) -> BillStatus.DUE_SOON
        else -> BillStatus.UPCOMING
    }
}

data class BillPayment(
    val id: Long = 0,
    val billId: Long,
    val dueDate: LocalDate,
    val paidAt: Long,
    val amountMinor: Long,
    val transactionId: Long?,
)

// ---- subscriptions ------------------------------------------------------------------------
enum class SubscriptionStatus(val label: String) { ACTIVE("Active"), PAUSED("Paused"), CANCELLED("Cancelled") }

data class Subscription(
    val id: Long = 0,
    val name: String,
    val amountMinor: Long,
    val cycle: Frequency,
    val startDate: LocalDate,
    val renewalDate: LocalDate,
    val accountId: Long?,
    val categoryId: Long?,
    val status: SubscriptionStatus,
    val reminderDays: List<Int>,
    val createdAt: Long = 0,
) {
    /** Cost of one month, in minor units (rounded). */
    val monthlyCostMinor: Long
        get() = when (cycle) {
            Frequency.DAILY -> amountMinor * 30
            Frequency.WEEKLY -> amountMinor * 52 / 12
            Frequency.MONTHLY -> amountMinor
            Frequency.QUARTERLY -> amountMinor / 3
            Frequency.HALF_YEARLY -> amountMinor / 6
            Frequency.YEARLY -> amountMinor / 12
            else -> amountMinor
        }

    /** Cost of one year, in minor units. */
    val annualCostMinor: Long
        get() = when (cycle) {
            Frequency.DAILY -> amountMinor * 365
            Frequency.WEEKLY -> amountMinor * 52
            Frequency.MONTHLY -> amountMinor * 12
            Frequency.QUARTERLY -> amountMinor * 4
            Frequency.HALF_YEARLY -> amountMinor * 2
            Frequency.YEARLY -> amountMinor
            else -> amountMinor
        }
}

data class SubscriptionPayment(
    val id: Long = 0,
    val subscriptionId: Long,
    val dueDate: LocalDate,
    val paidAt: Long,
    val amountMinor: Long,
    val transactionId: Long?,
)

// ---- debts --------------------------------------------------------------------------------
enum class LoanType(val label: String) {
    PERSONAL("Personal loan"), EDUCATION("Education loan"), VEHICLE("Vehicle loan"), HOME("Home loan"),
    CREDIT_CARD("Credit card balance"), BORROWED("Borrowed money"), OTHER("Other liability"),
}

data class Loan(
    val id: Long = 0,
    val lender: String,
    val type: LoanType,
    val principalMinor: Long,
    /** Outstanding amount when the loan was entered; payments reduce it from here. */
    val outstandingStartMinor: Long,
    val rateBps: Int,
    val emiMinor: Long,
    val frequency: Frequency,
    val startDate: LocalDate,
    val endDate: LocalDate?,
    val nextPaymentDate: LocalDate,
    val accountId: Long?,
    val isActive: Boolean,
    val createdAt: Long = 0,
)

data class LoanWithOutstanding(val loan: Loan, val outstandingMinor: Long, val paidMinor: Long)

data class LoanPayment(
    val id: Long = 0,
    val loanId: Long,
    val paidDate: LocalDate,
    val amountMinor: Long,
    val principalPartMinor: Long,
    val interestPartMinor: Long,
    val transactionId: Long?,
)

// ---- recurring ----------------------------------------------------------------------------
enum class EntryStatus { CONFIRMED, SKIPPED }

data class RecurringRule(
    val id: Long = 0,
    val title: String,
    val type: TransactionType,        // INCOME or EXPENSE only
    val amountMinor: Long,
    val categoryId: Long,
    val accountId: Long,
    val frequency: Frequency,
    val customDays: Int,
    val startDate: LocalDate,
    val nextDate: LocalDate,
    val endDate: LocalDate?,
    val isActive: Boolean,
    val createdAt: Long = 0,
)

data class RecurringEntry(
    val id: Long = 0,
    val ruleId: Long,
    val dueDate: LocalDate,
    val status: EntryStatus,
    val transactionId: Long?,
    val handledAt: Long,
)

// ---- misc ---------------------------------------------------------------------------------
data class Attachment(
    val id: Long = 0,
    val transactionId: Long,
    val filePath: String,
    val mimeType: String,
    val createdAt: Long,
)

/** 1 unit of [currencyCode] = [rateToBase] units of the base currency, as of [asOf] (epoch millis). */
data class ExchangeRate(val currencyCode: String, val rateToBase: String, val asOf: Long, val source: String)
