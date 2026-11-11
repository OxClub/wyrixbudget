package com.wyrix.budget.domain.model

import com.wyrix.budget.core.time.TimeRange

enum class AccountType(val label: String) {
    CASH("Cash wallet"),
    SAVINGS("Savings account"),
    CURRENT("Current account"),
    BANK("Bank account"),
    DIGITAL_WALLET("Digital wallet"),
    CREDIT_CARD("Credit card"),
    OTHER("Other"),
}

/**
 * INCOME and EXPENSE affect consolidated analytics. TRANSFER moves money between two accounts and
 * never counts as income or expense. ADJUSTMENT is a signed reconciliation correction and also never
 * counts as income or expense.
 */
enum class TransactionType { INCOME, EXPENSE, TRANSFER, ADJUSTMENT }

enum class CategoryKind { INCOME, EXPENSE }

enum class ExpenseGroup(val label: String) {
    ESSENTIAL("Essential"),
    LIFESTYLE("Lifestyle"),
    FINANCIAL("Financial"),
}

enum class ThemeMode { SYSTEM, DARK, LIGHT }

data class Account(
    val id: Long = 0,
    val name: String,
    val type: AccountType,
    val currencyCode: String = "INR",
    val openingBalanceMinor: Long,
    val description: String = "",
    val colorArgb: Int,
    val createdAt: Long,
)

/** The balance is always derived from the opening balance plus every recorded transaction. */
data class AccountWithBalance(val account: Account, val balanceMinor: Long)

data class Category(
    val id: Long = 0,
    val name: String,
    val kind: CategoryKind,
    val expenseGroup: ExpenseGroup? = null,
    val colorArgb: Int,
    val isDefault: Boolean = false,
)

/**
 * amountMinor is positive for INCOME, EXPENSE and TRANSFER. For ADJUSTMENT it is signed
 * (positive increases the account balance, negative decreases it).
 */
data class Transaction(
    val id: Long = 0,
    val type: TransactionType,
    val amountMinor: Long,
    val title: String,
    val categoryId: Long? = null,
    val accountId: Long,
    val transferAccountId: Long? = null,
    val occurredAt: Long,
    val merchant: String = "",
    val notes: String = "",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Original currency of the transaction (always the account's currency). */
    val currencyCode: String = "INR",
    /** Amount converted to the base currency, used for budgets, analytics and reports. */
    val baseAmountMinor: Long = 0,
    /** 1 unit of [currencyCode] = this many base units, recorded with [rateDate]. Null when no conversion. */
    val exchangeRate: String? = null,
    val rateDate: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.OTHER,
    val gstTagged: Boolean = false,
    val gstAmountMinor: Long? = null,
)

data class TransactionListItem(
    val transaction: Transaction,
    val categoryName: String?,
    val categoryColorArgb: Int?,
    val accountName: String,
    val transferAccountName: String?,
)

data class PeriodTotals(val incomeMinor: Long, val expenseMinor: Long) {
    val netMinor: Long get() = incomeMinor - expenseMinor
}

data class TransactionFilter(
    val query: String = "",
    val type: TransactionType? = null,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val range: TimeRange? = null,
    val ascending: Boolean = false,
)

/** Result of a domain action that can be rejected for user-correctable reasons. */
sealed interface ActionResult<out T> {
    data class Success<T>(val value: T) : ActionResult<T>
    data class Rejected(val messages: List<String>) : ActionResult<Nothing>
}

/** Pool of accent colors used for categories and accounts. */
object ColorPalette {
    val colors: List<Int> = listOf(
        0xFF55D6A5, 0xFF6C9EFF, 0xFFF5B942, 0xFFF16C75,
        0xFFB38CF5, 0xFF4FC3D9, 0xFFF58F5C, 0xFF8FD66C,
        0xFFE57AC0, 0xFF7C8CF5, 0xFF3FB8AF, 0xFFC9A27A,
    ).map { it.toInt() }
}
