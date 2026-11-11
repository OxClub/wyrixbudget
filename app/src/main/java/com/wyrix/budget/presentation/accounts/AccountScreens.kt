package com.wyrix.budget.presentation.accounts

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.ColorPalette
import com.wyrix.budget.presentation.components.ColorDot
import com.wyrix.budget.presentation.components.EmptyState
import com.wyrix.budget.presentation.components.MoneyText
import com.wyrix.budget.presentation.components.TransactionRow

// ---- Accounts list ----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(
    onBack: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onAddAccount: () -> Unit,
    onTransfer: () -> Unit,
    viewModel: AccountsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = onTransfer, enabled = state.accounts.size >= 2) {
                        Icon(Icons.Filled.SwapHoriz, contentDescription = "Transfer between accounts")
                    }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddAccount,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Add account") },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            state.accounts.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    title = "No accounts yet",
                    message = "Add a cash wallet, bank account or credit card to begin.",
                    actionLabel = "Add account",
                    onAction = onAddAccount,
                )
            }

            else -> LazyColumn(
                modifier = Modifier.padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "Total balance",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MoneyText(state.totalBalanceMinor, state.hideAmounts, style = MaterialTheme.typography.headlineMedium, currency = state.baseCurrency)
                            if (state.excludedAccounts.isNotEmpty()) Text("Not included (no exchange rate): " + state.excludedAccounts.joinToString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    }
                }
                items(state.accounts, key = { it.account.id }) { item ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().clickable { onOpenAccount(item.account.id) },
                    ) {
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ColorDot(Color(item.account.colorArgb), size = 16)
                            Column(Modifier.weight(1f)) {
                                Text(item.account.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    item.account.type.label,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            MoneyText(item.balanceMinor, state.hideAmounts, style = MaterialTheme.typography.titleMedium, currency = com.wyrix.budget.core.money.CurrencyInfo.byCode(item.account.currencyCode))
                        }
                    }
                }
                item { Box(Modifier.size(72.dp)) }
            }
        }
    }
}

// ---- Add account -----------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddAccountScreen(
    onBack: () -> Unit,
    viewModel: AddAccountViewModel = hiltViewModel(),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.done.collect { onBack() } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Add account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = form.name,
                onValueChange = viewModel::onNameChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Account name") },
                singleLine = true,
            )

            Text("Currency", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                com.wyrix.budget.core.money.CurrencyInfo.ALL.forEach { c ->
                    FilterChip(selected = form.currencyCode == c.code, onClick = { viewModel.onCurrencyChange(c.code) }, label = { Text(c.code) })
                }
            }

            Text("Type", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountType.entries.forEach { type ->
                    FilterChip(
                        selected = form.type == type,
                        onClick = { viewModel.onTypeChange(type) },
                        label = { Text(type.label) },
                    )
                }
            }

            OutlinedTextField(
                value = form.openingBalanceText,
                onValueChange = viewModel::onOpeningBalanceChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Opening balance") },
                prefix = { Text(com.wyrix.budget.core.money.CurrencyInfo.byCode(form.currencyCode).symbol.trim()) },
                supportingText = {
                    Text(
                        if (form.type == AccountType.CREDIT_CARD) "Use a negative number for an amount you owe, e.g. -4500"
                        else "Leave empty for zero",
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )

            OutlinedTextField(
                value = form.description,
                onValueChange = viewModel::onDescriptionChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Description (optional)") },
                minLines = 2,
            )

            Text("Color", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ColorPalette.colors.forEach { argb ->
                    val selected = form.colorArgb == argb
                    Box(
                        Modifier
                            .size(36.dp)
                            .background(Color(argb), CircleShape)
                            .clickable { viewModel.onColorChange(argb) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) {
                            Box(
                                Modifier
                                    .size(14.dp)
                                    .background(MaterialTheme.colorScheme.background, CircleShape),
                            )
                        }
                    }
                }
            }

            form.errors.forEach { message ->
                Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }

            Button(
                onClick = viewModel::save,
                enabled = !form.isSaving,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save account") }
        }
    }
}

// ---- Account detail ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountDetailScreen(
    onBack: () -> Unit,
    onEditTransaction: (Long) -> Unit,
    viewModel: AccountDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val items = viewModel.accountTransactions.collectAsLazyPagingItems()
    val snackbar = remember { SnackbarHostState() }
    var showReconcile by rememberSaveable { mutableStateOf(false) }
    var showArchive by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                AccountDetailEvent.Closed -> onBack()
                is AccountDetailEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val account = state.account

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(account?.account?.name ?: "Account") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            account == null -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Account not found", "It may have been archived.", actionLabel = "Go back", onAction = onBack)
            }

            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                account.account.type.label,
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            MoneyText(account.balanceMinor, state.hideAmounts, style = MaterialTheme.typography.headlineLarge, currency = com.wyrix.budget.core.money.CurrencyInfo.byCode(account.account.currencyCode))
                            Text(
                                "Opening balance " + if (state.hideAmounts) "••••••" else MoneyFormatter.format(account.account.openingBalanceMinor, account.account.currencyCode),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (account.account.description.isNotBlank()) {
                                Text(account.account.description, style = MaterialTheme.typography.bodyMedium)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { showReconcile = true }) { Text("Reconcile") }
                                TextButton(onClick = { showArchive = true }) { Text("Archive") }
                            }
                        }
                    }
                }

                item {
                    Text(
                        "Transactions",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                if (items.itemCount == 0) {
                    item {
                        Text(
                            "No transactions on this account yet.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp),
                        )
                    }
                } else {
                    items(count = items.itemCount, key = items.itemKey { it.transaction.id }) { index ->
                        val item = items[index]
                        if (item != null) {
                            TransactionRow(item, state.hideAmounts, onClick = { onEditTransaction(item.transaction.id) })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }
        }
    }

    if (showReconcile && account != null) {
        var actual by rememberSaveable { mutableStateOf("") }
        var reason by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReconcile = false },
            title = { Text("Reconcile balance") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "Budget calculates " + MoneyFormatter.format(account.balanceMinor, account.account.currencyCode) +
                            ". Enter the balance your bank or wallet shows. A recorded adjustment will cover any difference.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = actual,
                        onValueChange = { v ->
                            val n = v.replace(',', '.')
                            if (Regex("""^-?\d{0,10}(\.\d{0,2})?$""").matches(n)) actual = n
                        },
                        label = { Text("Actual balance") },
                        prefix = { Text(com.wyrix.budget.core.money.CurrencyInfo.byCode(account.account.currencyCode).symbol.trim()) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(
                        value = reason,
                        onValueChange = { reason = it.take(200) },
                        label = { Text("Reason") },
                        minLines = 2,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.reconcile(actual, reason)
                    showReconcile = false
                    actual = ""
                    reason = ""
                }) { Text("Reconcile") }
            },
            dismissButton = { TextButton(onClick = { showReconcile = false }) { Text("Cancel") } },
        )
    }

    if (showArchive) {
        AlertDialog(
            onDismissRequest = { showArchive = false },
            title = { Text("Archive account?") },
            text = {
                Text(
                    "The account is hidden from your lists but its history is kept. " +
                        "It can only be archived when its balance is zero.",
                )
            },
            confirmButton = { TextButton(onClick = { showArchive = false; viewModel.archive() }) { Text("Archive") } },
            dismissButton = { TextButton(onClick = { showArchive = false }) { Text("Cancel") } },
        )
    }
}
