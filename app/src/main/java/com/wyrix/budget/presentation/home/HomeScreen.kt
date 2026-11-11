package com.wyrix.budget.presentation.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wyrix.budget.presentation.components.ColorDot
import com.wyrix.budget.presentation.components.MoneyText
import com.wyrix.budget.presentation.components.TransactionRow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddExpense: () -> Unit,
    onAddIncome: () -> Unit,
    onOpenAccounts: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onViewAllTransactions: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    onOpenMore: () -> Unit,
    onForecast: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var fabExpanded by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = fabExpanded) { fabExpanded = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Budget") },
                actions = {
                    IconButton(onClick = viewModel::toggleHideAmounts) {
                        Icon(
                            imageVector = if (state.hideAmounts) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (state.hideAmounts) "Show amounts" else "Hide amounts",
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AnimatedVisibility(visible = fabExpanded) {
                    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExtendedFloatingActionButton(
                            onClick = { fabExpanded = false; onAddIncome() },
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            text = { Text("Add Income") },
                        )
                        ExtendedFloatingActionButton(
                            onClick = { fabExpanded = false; onAddExpense() },
                            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                            text = { Text("Add Expense") },
                        )
                    }
                }
                FloatingActionButton(onClick = { fabExpanded = !fabExpanded }) {
                    Icon(
                        imageVector = if (fabExpanded) Icons.Filled.Close else Icons.Filled.Add,
                        contentDescription = if (fabExpanded) "Close menu" else "Add transaction",
                    )
                }
            }
        },
    ) { padding ->
        if (state.isLoading) {
            Column(
                Modifier.padding(padding).fillMaxWidth().padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) { CircularProgressIndicator() }
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { SummaryCard(state) }

            item {
                SectionHeader(title = "Accounts", actionLabel = "Manage", onAction = onOpenAccounts)
                Spacer(Modifier.height(8.dp))
                if (state.accounts.isEmpty()) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Add your first account", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Create a cash wallet or bank account to start recording income and expenses.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Button(onClick = onAddAccount) { Text("Add account") }
                        }
                    }
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.accounts, key = { it.account.id }) { item ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                                modifier = Modifier.width(160.dp).clickable { onOpenAccount(item.account.id) },
                            ) {
                                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        ColorDot(Color(item.account.colorArgb))
                                        Text(
                                            item.account.name,
                                            modifier = Modifier.padding(start = 8.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                        )
                                    }
                                    MoneyText(
                                        minor = item.balanceMinor,
                                        currency = com.wyrix.budget.core.money.CurrencyInfo.byCode(item.account.currencyCode),
                                        hidden = state.hideAmounts,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenMore) { Text("Bills, goals and more") }
                    OutlinedButton(onClick = onForecast) { Text("Forecast") }
                }
            }

            item { SectionHeader(title = "Recent transactions", actionLabel = "View all", onAction = onViewAllTransactions) }

            if (state.recent.isEmpty()) {
                item {
                    Text(
                        "No transactions yet. Tap + to add your first income or expense.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                items(state.recent, key = { it.transaction.id }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        TransactionRow(
                            item = item,
                            hideAmounts = state.hideAmounts,
                            onClick = { onEditTransaction(item.transaction.id) },
                        )
                    }
                }
            }

            // Room for the floating button so it never covers the last row.
            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun SummaryCard(state: HomeUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Total balance",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                MoneyText(
                    minor = state.totalBalanceMinor,
                    hidden = state.hideAmounts,
                    currency = state.baseCurrency,
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Income this month",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(
                        minor = state.monthIncomeMinor,
                        hidden = state.hideAmounts,
                        currency = state.baseCurrency,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        "Spent this month",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    MoneyText(
                        minor = state.monthExpenseMinor,
                        hidden = state.hideAmounts,
                        currency = state.baseCurrency,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            if (state.excludedAccounts.isNotEmpty()) {
                Text(
                    "Not in the total (set an exchange rate in Settings): " + state.excludedAccounts.joinToString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, actionLabel: String, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = onAction) { Text(actionLabel) }
    }
}
