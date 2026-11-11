package com.wyrix.budget.presentation.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.wyrix.budget.domain.model.TransactionType
import com.wyrix.budget.presentation.accounts.AccountDetailScreen
import com.wyrix.budget.presentation.accounts.AccountsScreen
import com.wyrix.budget.presentation.accounts.AddAccountScreen
import com.wyrix.budget.presentation.analytics.AnalyticsScreen
import com.wyrix.budget.presentation.analytics.ForecastScreen
import com.wyrix.budget.presentation.bills.BillEditorScreen
import com.wyrix.budget.presentation.bills.BillsScreen
import com.wyrix.budget.presentation.budget.BudgetDetailScreen
import com.wyrix.budget.presentation.budget.BudgetEditorScreen
import com.wyrix.budget.presentation.budget.BudgetScreen
import com.wyrix.budget.presentation.calculators.CalculatorScreen
import com.wyrix.budget.presentation.calculators.CalculatorsHubScreen
import com.wyrix.budget.presentation.editor.TransactionEditorScreen
import com.wyrix.budget.presentation.goals.GoalDetailScreen
import com.wyrix.budget.presentation.goals.GoalEditorScreen
import com.wyrix.budget.presentation.goals.GoalsScreen
import com.wyrix.budget.presentation.home.HomeScreen
import com.wyrix.budget.presentation.loans.LoanDetailScreen
import com.wyrix.budget.presentation.loans.LoanEditorScreen
import com.wyrix.budget.presentation.loans.LoansScreen
import com.wyrix.budget.presentation.planner.MoreScreen
import com.wyrix.budget.presentation.recurring.RecurringEditorScreen
import com.wyrix.budget.presentation.recurring.RecurringScreen
import com.wyrix.budget.presentation.reports.ReportsScreen
import com.wyrix.budget.presentation.settings.CategoriesScreen
import com.wyrix.budget.presentation.settings.PrivacyScreen
import com.wyrix.budget.presentation.settings.RatesScreen
import com.wyrix.budget.presentation.settings.SettingsScreen
import com.wyrix.budget.presentation.subscriptions.SubscriptionEditorScreen
import com.wyrix.budget.presentation.subscriptions.SubscriptionsScreen
import com.wyrix.budget.presentation.transactions.TransactionsScreen

@Composable
fun WyrixApp(demo: Boolean, onExitDemo: () -> Unit) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val destination = backStackEntry?.destination
    val current = TopLevelDestination.entries.firstOrNull { top -> destination?.hierarchy?.any { it.route == top.route } == true }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (demo) Surface(color = MaterialTheme.colorScheme.tertiary) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("DEMO MODE · sample data only", color = MaterialTheme.colorScheme.onTertiary, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onExitDemo) { Text("Exit", color = MaterialTheme.colorScheme.onTertiary) }
                }
            }
        },
        bottomBar = {
            if (current != null) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TopLevelDestination.entries.forEach { top ->
                        NavigationBarItem(selected = top == current, onClick = { navController.navigateToTopLevel(top.route) }, icon = { Icon(top.icon, contentDescription = null) }, label = { Text(top.label) })
                    }
                }
            }
        },
    ) { innerPadding -> WyrixNavHost(navController, Modifier.padding(innerPadding)) }
}

private fun NavHostController.navigateToTopLevel(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private val optionalId = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType; defaultValue = -1L })
private val requiredId = listOf(navArgument(Routes.ARG_ID) { type = NavType.LongType })

@Composable
private fun WyrixNavHost(nav: NavHostController, modifier: Modifier) {
    val back: () -> Unit = { nav.popBackStack() }
    NavHost(navController = nav, startDestination = Routes.HOME, modifier = modifier) {
        composable(Routes.HOME) {
            HomeScreen(
                onAddExpense = { nav.navigate(Routes.newTransaction(TransactionType.EXPENSE)) },
                onAddIncome = { nav.navigate(Routes.newTransaction(TransactionType.INCOME)) },
                onOpenAccounts = { nav.navigate(Routes.ACCOUNTS) },
                onOpenAccount = { id -> nav.navigate(Routes.accountDetail(id)) },
                onAddAccount = { nav.navigate(Routes.ADD_ACCOUNT) },
                onViewAllTransactions = { nav.navigateToTopLevel(Routes.TRANSACTIONS) },
                onEditTransaction = { id -> nav.navigate(Routes.editTransaction(id)) },
                onOpenMore = { nav.navigateToTopLevel(Routes.PROFILE) },
                onForecast = { nav.navigate(Routes.FORECAST) },
            )
        }
        composable(Routes.TRANSACTIONS) {
            TransactionsScreen(
                onAddTransaction = { nav.navigate(Routes.newTransaction(TransactionType.EXPENSE)) },
                onEditTransaction = { id -> nav.navigate(Routes.editTransaction(id)) },
            )
        }
        composable(Routes.BUDGET) { BudgetScreen(onOpen = { nav.navigate(Routes.budgetDetail(it)) }, onAdd = { nav.navigate(Routes.budgetEditor()) }) }
        composable(Routes.ANALYTICS) { AnalyticsScreen(onForecast = { nav.navigate(Routes.FORECAST) }, onReports = { nav.navigate(Routes.REPORTS) }) }
        composable(Routes.PROFILE) {
            MoreScreen(
                onGoals = { nav.navigate(Routes.GOALS) }, onBills = { nav.navigate(Routes.BILLS) }, onSubscriptions = { nav.navigate(Routes.SUBSCRIPTIONS) },
                onLoans = { nav.navigate(Routes.LOANS) }, onRecurring = { nav.navigate(Routes.RECURRING) }, onCalculators = { nav.navigate(Routes.CALCULATORS) },
                onForecast = { nav.navigate(Routes.FORECAST) }, onReports = { nav.navigate(Routes.REPORTS) }, onAccounts = { nav.navigate(Routes.ACCOUNTS) },
                onSettings = { nav.navigate(Routes.SETTINGS) },
            )
        }

        // accounts and transactions
        composable(Routes.ACCOUNTS) {
            AccountsScreen(onBack = back, onOpenAccount = { nav.navigate(Routes.accountDetail(it)) }, onAddAccount = { nav.navigate(Routes.ADD_ACCOUNT) }, onTransfer = { nav.navigate(Routes.newTransaction(TransactionType.TRANSFER)) })
        }
        composable(Routes.ADD_ACCOUNT) { AddAccountScreen(onBack = back) }
        composable(Routes.ACCOUNT_DETAIL, arguments = listOf(navArgument(Routes.ARG_ACCOUNT_ID) { type = NavType.LongType })) {
            AccountDetailScreen(onBack = back, onEditTransaction = { nav.navigate(Routes.editTransaction(it)) })
        }
        composable(
            Routes.TRANSACTION_EDITOR,
            arguments = listOf(
                navArgument(Routes.ARG_TYPE) { type = NavType.StringType; defaultValue = TransactionType.EXPENSE.name },
                navArgument(Routes.ARG_ID) { type = NavType.LongType; defaultValue = -1L },
            ),
        ) { TransactionEditorScreen(onBack = back, onAddAccount = { nav.navigate(Routes.ADD_ACCOUNT) }) }

        // budgets
        composable(Routes.BUDGET_EDITOR, arguments = optionalId) { BudgetEditorScreen(onBack = back) }
        composable(Routes.BUDGET_DETAIL, arguments = requiredId) { BudgetDetailScreen(onBack = back, onEdit = { nav.navigate(Routes.budgetEditor(it)) }) }

        // planning
        composable(Routes.GOALS) { GoalsScreen(onBack = back, onOpen = { nav.navigate(Routes.goalDetail(it)) }, onAdd = { nav.navigate(Routes.goalEditor()) }) }
        composable(Routes.GOAL_EDITOR, arguments = optionalId) { GoalEditorScreen(onBack = back) }
        composable(Routes.GOAL_DETAIL, arguments = requiredId) { GoalDetailScreen(onBack = back, onEdit = { nav.navigate(Routes.goalEditor(it)) }) }
        composable(Routes.BILLS) { BillsScreen(onBack = back, onOpen = { nav.navigate(Routes.billEditor(it)) }, onAdd = { nav.navigate(Routes.billEditor()) }) }
        composable(Routes.BILL_EDITOR, arguments = optionalId) { BillEditorScreen(onBack = back) }
        composable(Routes.SUBSCRIPTIONS) { SubscriptionsScreen(onBack = back, onOpen = { nav.navigate(Routes.subscriptionEditor(it)) }, onAdd = { nav.navigate(Routes.subscriptionEditor()) }) }
        composable(Routes.SUBSCRIPTION_EDITOR, arguments = optionalId) { SubscriptionEditorScreen(onBack = back) }
        composable(Routes.LOANS) { LoansScreen(onBack = back, onOpen = { nav.navigate(Routes.loanDetail(it)) }, onAdd = { nav.navigate(Routes.loanEditor()) }, onPayoffPlanner = { nav.navigate(Routes.calculator("DEBT")) }) }
        composable(Routes.LOAN_EDITOR, arguments = optionalId) { LoanEditorScreen(onBack = back) }
        composable(Routes.LOAN_DETAIL, arguments = requiredId) { LoanDetailScreen(onBack = back, onEdit = { nav.navigate(Routes.loanEditor(it)) }) }
        composable(Routes.RECURRING) { RecurringScreen(onBack = back, onOpen = { nav.navigate(Routes.recurringEditor(it)) }, onAdd = { nav.navigate(Routes.recurringEditor()) }) }
        composable(Routes.RECURRING_EDITOR, arguments = optionalId) { RecurringEditorScreen(onBack = back) }
        composable(Routes.CALCULATORS) { CalculatorsHubScreen(onBack = back, onOpen = { nav.navigate(Routes.calculator(it)) }) }
        composable(Routes.CALCULATOR, arguments = listOf(navArgument(Routes.ARG_KIND) { type = NavType.StringType })) { entry: NavBackStackEntry ->
            CalculatorScreen(kind = entry.arguments?.getString(Routes.ARG_KIND) ?: "EMI", onBack = back)
        }
        composable(Routes.FORECAST) { ForecastScreen(onBack = back) }
        composable(Routes.REPORTS) { ReportsScreen(onBack = back) }

        // settings
        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = back, onCategories = { nav.navigate(Routes.CATEGORIES) }, onRates = { nav.navigate(Routes.RATES) }, onAccounts = { nav.navigate(Routes.ACCOUNTS) }, onPrivacy = { nav.navigate(Routes.PRIVACY) })
        }
        composable(Routes.CATEGORIES) { CategoriesScreen(onBack = back) }
        composable(Routes.RATES) { RatesScreen(onBack = back) }
        composable(Routes.PRIVACY) { PrivacyScreen(onBack = back) }
    }
}
