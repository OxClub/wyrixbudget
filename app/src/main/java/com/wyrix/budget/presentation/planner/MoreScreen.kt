package com.wyrix.budget.presentation.planner

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wyrix.budget.presentation.components.*

data class MoreEntry(val title: String, val blurb: String, val onClick: () -> Unit)

/** The "More" tab: every planning tool and settings in one place. */
@Composable
fun MoreScreen(
    onGoals: () -> Unit, onBills: () -> Unit, onSubscriptions: () -> Unit, onLoans: () -> Unit, onRecurring: () -> Unit,
    onCalculators: () -> Unit, onForecast: () -> Unit, onReports: () -> Unit, onAccounts: () -> Unit, onGstInvoice: () -> Unit, onSettings: () -> Unit,
) {
    val entries = listOf(
        MoreEntry("Savings goals", "Save toward something specific", onGoals),
        MoreEntry("Bills", "Due dates and reminders", onBills),
        MoreEntry("Subscriptions", "What your memberships really cost", onSubscriptions),
        MoreEntry("Debts and loans", "Balances, payments and payoff plans", onLoans),
        MoreEntry("Recurring items", "Salary, rent and other regular entries", onRecurring),
        MoreEntry("Balance forecast", "Where your balance is heading", onForecast),
        MoreEntry("Calculators", "EMI, savings, tax estimate and more", onCalculators),
        MoreEntry("GST invoice generator", "Create GST invoices and export professional PDFs", onGstInvoice),
        MoreEntry("Reports", "Export PDF, Excel or CSV", onReports),
        MoreEntry("Accounts", "Cash, bank, cards and wallets", onAccounts),
        MoreEntry("Settings", "Security, backup, notifications and data", onSettings),
    )
    ScreenScaffold("More") { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(entries, key = { it.title }) { e ->
                InfoCard(Modifier.clickable(onClick = e.onClick)) { Text(e.title, style = MaterialTheme.typography.titleMedium); Note(e.blurb) }
            }
        }
    }
}
