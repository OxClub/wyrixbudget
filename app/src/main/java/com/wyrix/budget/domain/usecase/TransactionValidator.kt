package com.wyrix.budget.domain.usecase

import com.wyrix.budget.core.money.MAX_AMOUNT_MINOR
import com.wyrix.budget.domain.model.Transaction
import com.wyrix.budget.domain.model.TransactionType

/** Pure validation rules for a transaction. Returns user-readable messages; empty means valid. */
object TransactionValidator {
    const val MAX_TITLE = 80
    const val MAX_MERCHANT = 80
    const val MAX_NOTES = 500

    fun validate(t: Transaction): List<String> {
        val errors = mutableListOf<String>()

        when (t.type) {
            TransactionType.ADJUSTMENT ->
                if (t.amountMinor == 0L) errors += "Adjustment amount cannot be zero."
            else ->
                if (t.amountMinor <= 0L) errors += "Enter an amount greater than zero."
        }
        if (t.amountMinor > MAX_AMOUNT_MINOR || t.amountMinor < -MAX_AMOUNT_MINOR) {
            errors += "That amount is too large."
        }

        if (t.accountId <= 0L) errors += "Choose an account."

        when (t.type) {
            TransactionType.INCOME, TransactionType.EXPENSE -> {
                if (t.categoryId == null) errors += "Choose a category."
                if (t.transferAccountId != null) errors += "Only transfers can have a destination account."
            }
            TransactionType.TRANSFER -> {
                if (t.transferAccountId == null) {
                    errors += "Choose a destination account."
                } else if (t.transferAccountId == t.accountId) {
                    errors += "Source and destination accounts must be different."
                }
                if (t.categoryId != null) errors += "Transfers cannot have a category."
            }
            TransactionType.ADJUSTMENT -> Unit
        }

        if (t.title.length > MAX_TITLE) errors += "Title must be at most $MAX_TITLE characters."
        if (t.merchant.length > MAX_MERCHANT) errors += "Merchant must be at most $MAX_MERCHANT characters."
        if (t.notes.length > MAX_NOTES) errors += "Notes must be at most $MAX_NOTES characters."

        return errors
    }
}
