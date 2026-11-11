package com.wyrix.budget.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.PaymentMethod
import com.wyrix.budget.domain.model.ExpenseGroup
import com.wyrix.budget.domain.model.TransactionType

@Entity(
    tableName = "accounts",
    indices = [
        Index(value = ["name"], unique = true),
        Index(value = ["isArchived"]),
    ],
)
data class AccountEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val type: AccountType,
    val currencyCode: String,
    val openingBalanceMinor: Long,
    val description: String,
    val colorArgb: Int,
    val isArchived: Boolean = false,
    val sortOrder: Int = 0,
    val createdAt: Long,
)

@Entity(
    tableName = "categories",
    indices = [Index(value = ["kind", "name"], unique = true)],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(collate = ColumnInfo.NOCASE) val name: String,
    val kind: CategoryKind,
    val expenseGroup: ExpenseGroup?,
    val colorArgb: Int,
    val isDefault: Boolean,
)

@Entity(
    tableName = "transactions",
    foreignKeys = [
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["accountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = AccountEntity::class,
            parentColumns = ["id"],
            childColumns = ["transferAccountId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["transferAccountId"]),
        Index(value = ["categoryId"]),
        Index(value = ["occurredAt"]),
        Index(value = ["type"]),
    ],
)
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: TransactionType,
    val amountMinor: Long,
    val title: String,
    val categoryId: Long?,
    val accountId: Long,
    val transferAccountId: Long?,
    val occurredAt: Long,
    val merchant: String,
    val notes: String,
    val createdAt: Long,
    val updatedAt: Long,
    val currencyCode: String,
    val baseAmountMinor: Long,
    val exchangeRate: String?,
    val rateDate: Long?,
    val paymentMethod: PaymentMethod,
    val gstTagged: Boolean,
    val gstAmountMinor: Long?,
)

/** Query result: an account plus its balance derived from the transaction records. */
data class AccountWithBalanceRow(
    @Embedded val account: AccountEntity,
    val balanceMinor: Long,
)

/** Query result: a transaction joined with the display names the UI needs. */
data class TransactionRow(
    @Embedded val transaction: TransactionEntity,
    val categoryName: String?,
    val categoryColorArgb: Int?,
    val accountName: String,
    val transferAccountName: String?,
)

data class PeriodTotalsRow(
    val incomeMinor: Long,
    val expenseMinor: Long,
)
