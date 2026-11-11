package com.wyrix.budget.domain

import com.wyrix.budget.core.money.MAX_AMOUNT_MINOR
import com.wyrix.budget.domain.model.Transaction
import com.wyrix.budget.domain.model.TransactionType
import com.wyrix.budget.domain.usecase.TransactionValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionValidatorTest {

    private fun expense(
        amount: Long = 10_000,
        categoryId: Long? = 1,
        accountId: Long = 1,
        transferTo: Long? = null,
        title: String = "Lunch",
    ) = Transaction(
        type = TransactionType.EXPENSE, amountMinor = amount, title = title,
        categoryId = categoryId, accountId = accountId, transferAccountId = transferTo, occurredAt = 0,
    )

    private fun transfer(from: Long = 1, to: Long? = 2, amount: Long = 10_000, categoryId: Long? = null) = Transaction(
        type = TransactionType.TRANSFER, amountMinor = amount, title = "Transfer",
        categoryId = categoryId, accountId = from, transferAccountId = to, occurredAt = 0,
    )

    @Test fun validExpensePasses() {
        assertTrue(TransactionValidator.validate(expense()).isEmpty())
    }

    @Test fun zeroOrNegativeAmountIsRejected() {
        assertEquals(1, TransactionValidator.validate(expense(amount = 0)).size)
        assertEquals(1, TransactionValidator.validate(expense(amount = -5)).size)
    }

    @Test fun oversizedAmountIsRejected() {
        assertTrue(TransactionValidator.validate(expense(amount = MAX_AMOUNT_MINOR + 1)).isNotEmpty())
    }

    @Test fun incomeAndExpenseNeedACategory() {
        assertTrue(TransactionValidator.validate(expense(categoryId = null)).contains("Choose a category."))
    }

    @Test fun missingAccountIsRejected() {
        assertTrue(TransactionValidator.validate(expense(accountId = 0)).contains("Choose an account."))
    }

    @Test fun expenseCannotCarryADestinationAccount() {
        assertTrue(TransactionValidator.validate(expense(transferTo = 2)).isNotEmpty())
    }

    @Test fun validTransferPasses() {
        assertTrue(TransactionValidator.validate(transfer()).isEmpty())
    }

    @Test fun transferNeedsDifferentAccounts() {
        assertTrue(TransactionValidator.validate(transfer(from = 1, to = 1)).isNotEmpty())
    }

    @Test fun transferNeedsADestination() {
        assertTrue(TransactionValidator.validate(transfer(to = null)).isNotEmpty())
    }

    @Test fun transferCannotHaveACategory() {
        assertTrue(TransactionValidator.validate(transfer(categoryId = 3)).isNotEmpty())
    }

    @Test fun adjustmentsMayBeNegativeButNotZero() {
        val base = Transaction(
            type = TransactionType.ADJUSTMENT, amountMinor = -500, title = "Balance reconciliation",
            accountId = 1, occurredAt = 0,
        )
        assertTrue(TransactionValidator.validate(base).isEmpty())
        assertTrue(TransactionValidator.validate(base.copy(amountMinor = 0)).isNotEmpty())
    }

    @Test fun overlongTextIsRejected() {
        assertTrue(TransactionValidator.validate(expense(title = "x".repeat(81))).isNotEmpty())
    }
}
