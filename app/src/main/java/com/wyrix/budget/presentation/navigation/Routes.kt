package com.wyrix.budget.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.ui.graphics.vector.ImageVector
import com.wyrix.budget.domain.model.TransactionType

object Routes {
    const val HOME = "home"
    const val TRANSACTIONS = "transactions"
    const val BUDGET = "budget"
    const val ANALYTICS = "analytics"
    const val PROFILE = "profile"

    const val BUDGET_EDITOR = "budget/edit?id={id}"
    const val BUDGET_DETAIL = "budget/detail/{id}"
    const val GOALS = "goals"
    const val GOAL_EDITOR = "goals/edit?id={id}"
    const val GOAL_DETAIL = "goals/detail/{id}"
    const val BILLS = "bills"
    const val BILL_EDITOR = "bills/edit?id={id}"
    const val SUBSCRIPTIONS = "subscriptions"
    const val SUBSCRIPTION_EDITOR = "subscriptions/edit?id={id}"
    const val LOANS = "loans"
    const val LOAN_EDITOR = "loans/edit?id={id}"
    const val LOAN_DETAIL = "loans/detail/{id}"
    const val RECURRING = "recurring"
    const val RECURRING_EDITOR = "recurring/edit?id={id}"
    const val CALCULATORS = "calculators"
    const val CALCULATOR = "calculators/{kind}"
    const val FORECAST = "forecast"
    const val REPORTS = "reports"
    const val GST_INVOICE = "gst-invoice"
    const val SETTINGS = "settings"
    const val CATEGORIES = "settings/categories"
    const val RATES = "settings/rates"
    const val PRIVACY = "settings/privacy"
    const val ARG_KIND = "kind"

    const val ACCOUNTS = "accounts"
    const val ADD_ACCOUNT = "accounts/add"
    const val ACCOUNT_DETAIL = "account/{accountId}"

    const val TRANSACTION_EDITOR = "transaction?type={type}&id={id}"

    const val ARG_ACCOUNT_ID = "accountId"
    const val ARG_TYPE = "type"
    const val ARG_ID = "id"

    fun accountDetail(id: Long) = "account/$id"
    fun budgetDetail(id: Long) = "budget/detail/$id"
    fun budgetEditor(id: Long = -1) = "budget/edit?id=$id"
    fun goalDetail(id: Long) = "goals/detail/$id"
    fun goalEditor(id: Long = -1) = "goals/edit?id=$id"
    fun billEditor(id: Long = -1) = "bills/edit?id=$id"
    fun subscriptionEditor(id: Long = -1) = "subscriptions/edit?id=$id"
    fun loanDetail(id: Long) = "loans/detail/$id"
    fun loanEditor(id: Long = -1) = "loans/edit?id=$id"
    fun recurringEditor(id: Long = -1) = "recurring/edit?id=$id"
    fun calculator(kind: String) = "calculators/$kind"
    fun newTransaction(type: TransactionType) = "transaction?type=${type.name}&id=-1"
    fun editTransaction(id: Long) = "transaction?type=${TransactionType.EXPENSE.name}&id=$id"
}

enum class TopLevelDestination(val route: String, val label: String, val icon: ImageVector) {
    HOME(Routes.HOME, "Home", Icons.Filled.Home),
    TRANSACTIONS(Routes.TRANSACTIONS, "Transactions", Icons.Filled.Receipt),
    BUDGET(Routes.BUDGET, "Budget", Icons.Filled.PieChart),
    ANALYTICS(Routes.ANALYTICS, "Analytics", Icons.Filled.BarChart),
    PROFILE(Routes.PROFILE, "More", Icons.Filled.MoreHoriz),
}
