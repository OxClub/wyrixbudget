package com.wyrix.budget.domain.usecase

import com.wyrix.budget.core.money.CurrencyConversion
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MAX_AMOUNT_MINOR
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.domain.model.Account
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.ActionResult
import com.wyrix.budget.domain.model.Category
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.ColorPalette
import com.wyrix.budget.domain.model.ExpenseGroup
import com.wyrix.budget.domain.model.Transaction
import com.wyrix.budget.domain.model.TransactionType
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.BaseCurrencyProvider
import com.wyrix.budget.domain.repository.CategoryRepository
import com.wyrix.budget.domain.repository.DuplicateNameException
import com.wyrix.budget.domain.repository.TransactionRepository
import java.time.Clock
import javax.inject.Inject

/**
 * Validates and stores an income, expense or transfer, creating it when id == 0 and editing it
 * otherwise. Balances are derived from the stored records, so an edit automatically replaces the
 * previous effect with the corrected one. A transaction is always stored in its account's currency;
 * when that differs from the base currency the user-supplied exchange rate is recorded with it and
 * used to compute the base amount (budgets, analytics and reports use the base amount).
 */
class SaveTransactionUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val categories: CategoryRepository,
    private val accounts: AccountRepository,
    private val baseCurrency: BaseCurrencyProvider,
    private val clock: Clock,
) {
    suspend operator fun invoke(input: Transaction): ActionResult<Long> {
        val now = clock.millis()
        val normalized = input.copy(
            title = input.title.trim(),
            merchant = input.merchant.trim(),
            notes = input.notes.trim(),
        )

        val errors = TransactionValidator.validate(normalized)
        if (errors.isNotEmpty()) return ActionResult.Rejected(errors)

        val source = accounts.getActive(normalized.accountId)
            ?: return ActionResult.Rejected(listOf("The selected account is no longer available."))
        val currency = source.account.currencyCode

        val destinationId = normalized.transferAccountId
        if (destinationId != null) {
            val destination = accounts.getActive(destinationId)
                ?: return ActionResult.Rejected(listOf("The destination account is no longer available."))
            if (destination.account.currencyCode != currency) {
                return ActionResult.Rejected(
                    listOf("Transfers between accounts in different currencies aren't supported. Record an expense and an income instead."),
                )
            }
        }

        var categoryName: String? = null
        val categoryId = normalized.categoryId
        if (categoryId != null) {
            val category = categories.getById(categoryId)
                ?: return ActionResult.Rejected(listOf("The selected category no longer exists."))
            val expectedKind =
                if (normalized.type == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
            if (category.kind != expectedKind) {
                return ActionResult.Rejected(listOf("That category can't be used for this transaction type."))
            }
            categoryName = category.name
        }

        val base = baseCurrency.code()
        var baseMinor = normalized.amountMinor
        var rateText: String? = null
        var rateDate: Long? = null
        val countsInTotals = normalized.type == TransactionType.INCOME || normalized.type == TransactionType.EXPENSE
        if (countsInTotals && currency != base) {
            val rate = CurrencyConversion.parseRate(normalized.exchangeRate)
                ?: return ActionResult.Rejected(listOf("Enter the exchange rate from $currency to $base."))
            baseMinor = CurrencyConversion.convert(
                normalized.amountMinor, CurrencyInfo.byCode(currency), CurrencyInfo.byCode(base), rate,
            )
            rateText = rate.stripTrailingZeros().toPlainString()
            rateDate = normalized.rateDate ?: now
        }

        val gstAmount = if (normalized.gstTagged) normalized.gstAmountMinor else null
        if (gstAmount != null && (gstAmount < 0 || gstAmount > normalized.amountMinor)) {
            return ActionResult.Rejected(listOf("GST amount can't be more than the transaction amount."))
        }

        val title = normalized.title.ifEmpty {
            when (normalized.type) {
                TransactionType.TRANSFER -> "Transfer"
                else -> categoryName ?: "Transaction"
            }
        }
        val prepared = normalized.copy(
            title = title,
            currencyCode = currency,
            baseAmountMinor = baseMinor,
            exchangeRate = rateText,
            rateDate = rateDate,
            gstAmountMinor = gstAmount,
        )

        if (normalized.id == 0L) {
            return ActionResult.Success(transactions.insert(prepared.copy(createdAt = now, updatedAt = now)))
        }

        val existing = transactions.getById(normalized.id)
            ?: return ActionResult.Rejected(listOf("This transaction no longer exists."))
        if (existing.type == TransactionType.ADJUSTMENT) {
            return ActionResult.Rejected(
                listOf("Reconciliation adjustments can't be edited. Delete it and reconcile again instead."),
            )
        }
        if (existing.type != normalized.type) {
            return ActionResult.Rejected(listOf("The type of an existing transaction can't be changed."))
        }
        val toUpdate = prepared.copy(createdAt = existing.createdAt, updatedAt = now)
        return if (transactions.update(toUpdate)) {
            ActionResult.Success(toUpdate.id)
        } else {
            ActionResult.Rejected(listOf("This transaction no longer exists."))
        }
    }
}

class DeleteTransactionUseCase @Inject constructor(
    private val transactions: TransactionRepository,
) {
    suspend operator fun invoke(id: Long): ActionResult<Unit> =
        if (transactions.delete(id)) ActionResult.Success(Unit)
        else ActionResult.Rejected(listOf("This transaction no longer exists."))
}

class DuplicateTransactionUseCase @Inject constructor(
    private val transactions: TransactionRepository,
    private val save: SaveTransactionUseCase,
    private val clock: Clock,
) {
    suspend operator fun invoke(id: Long): ActionResult<Long> {
        val original = transactions.getById(id)
            ?: return ActionResult.Rejected(listOf("This transaction no longer exists."))
        if (original.type == TransactionType.ADJUSTMENT) {
            return ActionResult.Rejected(listOf("Reconciliation adjustments can't be duplicated."))
        }
        return save(original.copy(id = 0, occurredAt = clock.millis(), createdAt = 0, updatedAt = 0))
    }
}

class CreateAccountUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        name: String,
        type: AccountType,
        openingBalanceMinor: Long,
        description: String,
        colorArgb: Int,
        currencyCode: String = "INR",
    ): ActionResult<Long> {
        val trimmedName = name.trim()
        val errors = mutableListOf<String>()
        if (trimmedName.isEmpty()) errors += "Enter an account name."
        if (trimmedName.length > MAX_ACCOUNT_NAME) errors += "Account name must be at most $MAX_ACCOUNT_NAME characters."
        if (description.trim().length > MAX_DESCRIPTION) errors += "Description must be at most $MAX_DESCRIPTION characters."
        if (openingBalanceMinor > MAX_AMOUNT_MINOR || openingBalanceMinor < -MAX_AMOUNT_MINOR) {
            errors += "Opening balance is out of range."
        }
        if (errors.isNotEmpty()) return ActionResult.Rejected(errors)

        return try {
            ActionResult.Success(
                accounts.create(
                    Account(
                        name = trimmedName,
                        type = type,
                        currencyCode = currencyCode,
                        openingBalanceMinor = openingBalanceMinor,
                        description = description.trim(),
                        colorArgb = colorArgb,
                        createdAt = clock.millis(),
                    ),
                ),
            )
        } catch (e: DuplicateNameException) {
            ActionResult.Rejected(listOf("An account with this name already exists."))
        }
    }

    companion object {
        const val MAX_ACCOUNT_NAME = 40
        const val MAX_DESCRIPTION = 200
    }
}

/** Archiving hides a closed account but keeps all its history. Only allowed at a zero balance. */
class ArchiveAccountUseCase @Inject constructor(
    private val accounts: AccountRepository,
) {
    suspend operator fun invoke(accountId: Long): ActionResult<Unit> {
        val account = accounts.getActive(accountId)
            ?: return ActionResult.Rejected(listOf("This account no longer exists."))
        if (account.balanceMinor != 0L) {
            return ActionResult.Rejected(
                listOf(
                    "Balance is ${MoneyFormatter.format(account.balanceMinor, account.account.currencyCode)}. " +
                        "Transfer it out or reconcile to zero before archiving.",
                ),
            )
        }
        accounts.archive(accountId)
        return ActionResult.Success(Unit)
    }
}

/** Compares the calculated balance with the real one and records a signed, timestamped adjustment. */
class ReconcileAccountUseCase @Inject constructor(
    private val accounts: AccountRepository,
    private val transactions: TransactionRepository,
    private val clock: Clock,
) {
    /** Success value is the difference (actual minus calculated); 0 means no adjustment was needed. */
    suspend operator fun invoke(accountId: Long, actualBalanceMinor: Long, reason: String): ActionResult<Long> {
        val trimmedReason = reason.trim()
        val errors = mutableListOf<String>()
        if (trimmedReason.isEmpty()) errors += "Enter a reason for the adjustment."
        if (trimmedReason.length > TransactionValidator.MAX_NOTES) errors += "Reason is too long."
        if (actualBalanceMinor > MAX_AMOUNT_MINOR || actualBalanceMinor < -MAX_AMOUNT_MINOR) {
            errors += "Balance is out of range."
        }
        if (errors.isNotEmpty()) return ActionResult.Rejected(errors)
        if (accounts.getActive(accountId) == null) {
            return ActionResult.Rejected(listOf("This account no longer exists."))
        }
        return ActionResult.Success(
            transactions.reconcile(accountId, actualBalanceMinor, trimmedReason, clock.millis()),
        )
    }
}

class CreateCategoryUseCase @Inject constructor(
    private val categories: CategoryRepository,
) {
    suspend operator fun invoke(name: String, kind: CategoryKind, group: ExpenseGroup? = null): ActionResult<Long> {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return ActionResult.Rejected(listOf("Enter a category name."))
        if (trimmed.length > MAX_NAME) return ActionResult.Rejected(listOf("Category name must be at most $MAX_NAME characters."))

        val color = ColorPalette.colors[categories.count(kind) % ColorPalette.colors.size]
        return try {
            ActionResult.Success(
                categories.create(
                    Category(
                        name = trimmed,
                        kind = kind,
                        expenseGroup = if (kind == CategoryKind.EXPENSE) group else null,
                        colorArgb = color,
                        isDefault = false,
                    ),
                ),
            )
        } catch (e: DuplicateNameException) {
            ActionResult.Rejected(listOf("A category with this name already exists."))
        }
    }

    companion object {
        const val MAX_NAME = 30
    }
}
