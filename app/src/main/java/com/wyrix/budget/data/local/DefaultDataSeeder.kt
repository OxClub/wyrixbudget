package com.wyrix.budget.data.local

import com.wyrix.budget.data.local.dao.CategoryDao
import com.wyrix.budget.data.local.entity.CategoryEntity
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.ColorPalette
import com.wyrix.budget.domain.model.ExpenseGroup
import javax.inject.Inject

/** Inserts the default categories on first run. Safe to call repeatedly (unique index + IGNORE). */
class DefaultDataSeeder @Inject constructor(private val categoryDao: CategoryDao) {

    suspend fun seedIfEmpty() {
        if (categoryDao.countAll() > 0) return
        categoryDao.insertAll(defaultCategories())
    }

    private fun defaultCategories(): List<CategoryEntity> {
        val colors = ColorPalette.colors
        val result = mutableListOf<CategoryEntity>()

        INCOME_NAMES.forEachIndexed { i, name ->
            result += CategoryEntity(
                name = name, kind = CategoryKind.INCOME, expenseGroup = null,
                colorArgb = colors[i % colors.size], isDefault = true,
            )
        }

        var i = 0
        fun expense(group: ExpenseGroup?, names: List<String>) {
            names.forEach { name ->
                result += CategoryEntity(
                    name = name, kind = CategoryKind.EXPENSE, expenseGroup = group,
                    colorArgb = colors[i % colors.size], isDefault = true,
                )
                i++
            }
        }
        expense(ExpenseGroup.ESSENTIAL, ESSENTIAL_NAMES)
        expense(ExpenseGroup.LIFESTYLE, LIFESTYLE_NAMES)
        expense(ExpenseGroup.FINANCIAL, FINANCIAL_NAMES)
        expense(null, listOf("Other expenses"))
        return result
    }

    private companion object {
        val INCOME_NAMES = listOf(
            "Salary", "Business income", "Freelancing", "Rental income", "Interest income",
            "Bonuses", "Gifts", "Refunds", "Other income",
        )
        val ESSENTIAL_NAMES = listOf(
            "Groceries", "Housing", "Rent", "Electricity", "Water", "Internet",
            "Transportation", "Healthcare", "Education", "Insurance",
        )
        val LIFESTYLE_NAMES = listOf(
            "Restaurants", "Shopping", "Entertainment", "Travel", "Clothing",
            "Gaming", "Movies", "Hobbies", "Personal care",
        )
        val FINANCIAL_NAMES = listOf(
            "Loan payments", "Credit card payments", "Bank charges", "Taxes",
            "Other financial obligations",
        )
    }
}
