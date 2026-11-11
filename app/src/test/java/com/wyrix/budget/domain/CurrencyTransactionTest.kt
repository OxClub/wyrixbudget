package com.wyrix.budget.domain

import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.usecase.SaveTransactionUseCase
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CurrencyTransactionTest {
    private val now = Instant.parse("2026-11-09T10:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private lateinit var repo: FakeTransactions
    private lateinit var save: SaveTransactionUseCase

    @Before fun setUp() {
        repo = FakeTransactions()
        val cats = FakeCategories(listOf(Category(2, "Travel", CategoryKind.EXPENSE, colorArgb = 0)))
        save = SaveTransactionUseCase(repo, cats, FakeAccounts(1, 2, 3, currencies = mapOf(3L to "USD")), InrBase, clock)
    }

    private fun expense(account: Long, amount: Long = 1_000, rate: String? = null) = Transaction(
        type = TransactionType.EXPENSE, amountMinor = amount, title = "", categoryId = 2, accountId = account, occurredAt = 0, exchangeRate = rate,
    )

    @Test fun baseCurrencyTransactionsNeedNoRate() = runTest {
        val id = (save(expense(1)) as ActionResult.Success).value
        val t = repo.stored.getValue(id)
        assertEquals("INR", t.currencyCode); assertEquals(1_000L, t.baseAmountMinor); assertNull(t.exchangeRate)
    }

    @Test fun foreignTransactionsRequireAnExchangeRate() = runTest {
        assertTrue(save(expense(3)) is ActionResult.Rejected)
        assertTrue(save(expense(3, rate = "abc")) is ActionResult.Rejected)
        assertTrue(repo.stored.isEmpty())
    }

    @Test fun foreignTransactionsStoreTheOriginalAmountAndTheConvertedBaseAmount() = runTest {
        val id = (save(expense(3, amount = 1_000, rate = "83.25")) as ActionResult.Success).value
        val t = repo.stored.getValue(id)
        assertEquals("USD", t.currencyCode)
        assertEquals(1_000L, t.amountMinor)          // USD 10.00 as entered
        assertEquals(83_250L, t.baseAmountMinor)     // INR 832.50 used for budgets and reports
        assertEquals("83.25", t.exchangeRate)
        assertEquals(now.toEpochMilli(), t.rateDate)
    }

    @Test fun transfersBetweenCurrenciesAreRejected() = runTest {
        val t = Transaction(type = TransactionType.TRANSFER, amountMinor = 1_000, title = "", accountId = 1, transferAccountId = 3, occurredAt = 0)
        assertTrue(save(t) is ActionResult.Rejected)
        assertTrue(save(t.copy(transferAccountId = 2)) is ActionResult.Success)
    }

    @Test fun gstAmountCannotExceedTheTransaction() = runTest {
        assertTrue(save(expense(1).copy(gstTagged = true, gstAmountMinor = 2_000)) is ActionResult.Rejected)
        val id = (save(expense(1).copy(gstTagged = true, gstAmountMinor = 150)) as ActionResult.Success).value
        assertEquals(150L, repo.stored.getValue(id).gstAmountMinor)
    }

    @Test fun gstAmountIsDroppedWhenNotTagged() = runTest {
        val id = (save(expense(1).copy(gstTagged = false, gstAmountMinor = 150)) as ActionResult.Success).value
        assertNull(repo.stored.getValue(id).gstAmountMinor)
    }
}
