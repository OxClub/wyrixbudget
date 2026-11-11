package com.wyrix.budget.data.repository

import com.wyrix.budget.data.local.entity.AccountEntity
import com.wyrix.budget.data.local.entity.AccountWithBalanceRow
import com.wyrix.budget.data.local.entity.CategoryEntity
import com.wyrix.budget.data.local.entity.TransactionEntity
import com.wyrix.budget.data.local.entity.TransactionRow
import com.wyrix.budget.domain.model.Account
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.Category
import com.wyrix.budget.domain.model.Transaction
import com.wyrix.budget.domain.model.TransactionListItem

internal fun AccountEntity.toDomain() = Account(
    id = id, name = name, type = type, currencyCode = currencyCode,
    openingBalanceMinor = openingBalanceMinor, description = description,
    colorArgb = colorArgb, createdAt = createdAt,
)

internal fun Account.toEntity() = AccountEntity(
    id = id, name = name, type = type, currencyCode = currencyCode,
    openingBalanceMinor = openingBalanceMinor, description = description,
    colorArgb = colorArgb, createdAt = createdAt,
)

internal fun AccountWithBalanceRow.toDomain() =
    AccountWithBalance(account = account.toDomain(), balanceMinor = balanceMinor)

internal fun CategoryEntity.toDomain() = Category(
    id = id, name = name, kind = kind, expenseGroup = expenseGroup,
    colorArgb = colorArgb, isDefault = isDefault,
)

internal fun Category.toEntity() = CategoryEntity(
    id = id, name = name, kind = kind, expenseGroup = expenseGroup,
    colorArgb = colorArgb, isDefault = isDefault,
)

internal fun TransactionEntity.toDomain() = Transaction(
    id = id, type = type, amountMinor = amountMinor, title = title,
    categoryId = categoryId, accountId = accountId, transferAccountId = transferAccountId,
    occurredAt = occurredAt, merchant = merchant, notes = notes,
    createdAt = createdAt, updatedAt = updatedAt,
    currencyCode = currencyCode, baseAmountMinor = baseAmountMinor, exchangeRate = exchangeRate,
    rateDate = rateDate, paymentMethod = paymentMethod, gstTagged = gstTagged, gstAmountMinor = gstAmountMinor,
)

internal fun Transaction.toEntity() = TransactionEntity(
    id = id, type = type, amountMinor = amountMinor, title = title,
    categoryId = categoryId, accountId = accountId, transferAccountId = transferAccountId,
    occurredAt = occurredAt, merchant = merchant, notes = notes,
    createdAt = createdAt, updatedAt = updatedAt,
    currencyCode = currencyCode, baseAmountMinor = baseAmountMinor, exchangeRate = exchangeRate,
    rateDate = rateDate, paymentMethod = paymentMethod, gstTagged = gstTagged, gstAmountMinor = gstAmountMinor,
)

internal fun TransactionRow.toDomain() = TransactionListItem(
    transaction = transaction.toDomain(),
    categoryName = categoryName,
    categoryColorArgb = categoryColorArgb,
    accountName = accountName,
    transferAccountName = transferAccountName,
)
