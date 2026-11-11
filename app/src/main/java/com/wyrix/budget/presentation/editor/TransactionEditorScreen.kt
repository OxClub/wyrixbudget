package com.wyrix.budget.presentation.editor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.produceState
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
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.money.MoneyParser
import com.wyrix.budget.domain.model.TransactionType
import com.wyrix.budget.domain.model.PaymentMethod
import com.wyrix.budget.presentation.components.AppTextField
import com.wyrix.budget.presentation.components.ChipSelect
import com.wyrix.budget.presentation.components.ColorDot
import com.wyrix.budget.presentation.components.DatePickerField
import com.wyrix.budget.presentation.components.EmptyState
import com.wyrix.budget.presentation.components.TimePickerField

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TransactionEditorScreen(
    onBack: () -> Unit,
    onAddAccount: () -> Unit,
    viewModel: TransactionEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showDelete by rememberSaveable { mutableStateOf(false) }
    var showNewCategory by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                EditorEvent.Done -> onBack()
                is EditorEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    val form = state.form
    val isAdjustment = form.type == TransactionType.ADJUSTMENT
    val title = when {
        state.isEditing && isAdjustment -> "Reconciliation adjustment"
        state.isEditing -> "Edit transaction"
        form.type == TransactionType.INCOME -> "Add income"
        form.type == TransactionType.TRANSFER -> "Transfer money"
        else -> "Add expense"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.isEditing && form.isLoaded && !form.loadFailed) {
                        if (!isAdjustment) {
                            IconButton(onClick = viewModel::duplicate) {
                                Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate")
                            }
                        }
                        IconButton(onClick = { showDelete = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            !form.isLoaded -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            form.loadFailed -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Transaction not found", "It may have been deleted.", actionLabel = "Go back", onAction = onBack)
            }

            state.accountsLoaded && state.accounts.isEmpty() && !state.isEditing ->
                Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyState(
                        title = "Create an account first",
                        message = "Transactions are recorded against an account, such as a cash wallet or bank account.",
                        actionLabel = "Add account",
                        onAction = onAddAccount,
                    )
                }

            isAdjustment -> AdjustmentSummary(form, Modifier.padding(padding))

            else -> Column(
                modifier = Modifier
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!state.isEditing) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.TRANSFER).forEach { type ->
                            FilterChip(
                                selected = form.type == type,
                                onClick = { viewModel.onTypeChange(type) },
                                label = { Text(type.name.lowercase().replaceFirstChar { it.uppercase() }) },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = form.amountText,
                    onValueChange = viewModel::onAmountChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Amount") },
                    prefix = { Text(state.accountCurrency.symbol.trim()) },
                    textStyle = MaterialTheme.typography.headlineMedium,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )

                OutlinedTextField(
                    value = form.title,
                    onValueChange = viewModel::onTitleChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Title (optional)") },
                    singleLine = true,
                )

                if (form.type != TransactionType.TRANSFER) {
                    SectionLabel("Category")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.categories.forEach { category ->
                            FilterChip(
                                selected = form.categoryId == category.id,
                                onClick = { viewModel.onCategorySelected(category.id) },
                                label = { Text(category.name) },
                                leadingIcon = { ColorDot(Color(category.colorArgb), size = 10) },
                            )
                        }
                        AssistChip(
                            onClick = { showNewCategory = true },
                            label = { Text("New") },
                            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        )
                    }
                }

                SectionLabel(if (form.type == TransactionType.TRANSFER) "From account" else "Account")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.accounts.forEach { item ->
                        FilterChip(
                            selected = state.effectiveAccountId == item.account.id,
                            onClick = { viewModel.onAccountSelected(item.account.id) },
                            label = { Text(item.account.name) },
                            leadingIcon = { ColorDot(Color(item.account.colorArgb), size = 10) },
                        )
                    }
                }

                if (form.type == TransactionType.TRANSFER) {
                    SectionLabel("To account")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.accounts.filter { it.account.id != state.effectiveAccountId }.forEach { item ->
                            FilterChip(
                                selected = state.effectiveToAccountId == item.account.id,
                                onClick = { viewModel.onToAccountSelected(item.account.id) },
                                label = { Text(item.account.name) },
                                leadingIcon = { ColorDot(Color(item.account.colorArgb), size = 10) },
                            )
                        }
                    }
                    if (state.accounts.size < 2) {
                        Text(
                            "You need at least two accounts to make a transfer.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DatePickerField(form.date, viewModel::onDateChange, Modifier.weight(1f))
                    TimePickerField(form.time, viewModel::onTimeChange, Modifier.weight(1f))
                }

                if (form.type != TransactionType.TRANSFER) {
                    OutlinedTextField(
                        value = form.merchant,
                        onValueChange = viewModel::onMerchantChange,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Merchant (optional)") },
                        singleLine = true,
                    )
                }

                OutlinedTextField(
                    value = form.notes,
                    onValueChange = viewModel::onNotesChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Notes (optional)") },
                    minLines = 2,
                )

                if (state.needsRate) {
                    LaunchedEffect(state.accountCurrency.code, state.savedRate?.rateToBase) {
                        if (form.rateText.isBlank() && state.savedRate != null) viewModel.onRateChange(state.savedRate!!.rateToBase)
                    }
                    val stale = state.savedRate?.let { java.time.temporal.ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(it.asOf), java.time.Instant.now()) > 7 } ?: false
                    AppTextField(
                        value = form.rateText, onChange = viewModel::onRateChange,
                        label = "Exchange rate: 1 ${state.accountCurrency.code} = ? ${state.baseCurrency.code}",
                        keyboardType = KeyboardType.Decimal,
                        supporting = when {
                            state.savedRate == null -> "Enter today's rate. Save rates in Settings to reuse them."
                            stale -> "Your saved rate is more than 7 days old. Check it before saving."
                            else -> "From your saved rate."
                        },
                    )
                }

                if (form.type != TransactionType.TRANSFER) {
                    ChipSelect("Payment method", PaymentMethod.entries, form.paymentMethod, viewModel::onPaymentMethod) { it.label }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("GST invoice", Modifier.weight(1f))
                        Switch(checked = form.gstTagged, onCheckedChange = viewModel::onGstTagged)
                    }
                    if (form.gstTagged) {
                        AppTextField(
                            value = form.gstText, onChange = viewModel::onGstAmountChange, label = "GST amount (optional)",
                            keyboardType = KeyboardType.Decimal, prefix = state.accountCurrency.symbol.trim(),
                            supporting = "The tax included in the amount above",
                        )
                    }
                }

                ReceiptsSection(viewModel)

                form.errors.forEach { message ->
                    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }

                Button(
                    onClick = viewModel::save,
                    enabled = !form.isSaving,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (state.isEditing) "Save changes" else "Save") }
            }
        }
    }

    if (showDelete) {
        AlertDialog(
            onDismissRequest = { showDelete = false },
            title = { Text("Delete transaction?") },
            text = { Text("This removes the record and updates your account balances. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { showDelete = false; viewModel.delete() }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { showDelete = false }) { Text("Cancel") } },
        )
    }

    if (showNewCategory) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewCategory = false },
            title = { Text("New category") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(30) },
                    label = { Text("Name") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createCategory(name)
                    showNewCategory = false
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { showNewCategory = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AdjustmentSummary(form: EditorForm, modifier: Modifier = Modifier) {
    val signed = MoneyParser.parseOrNull(form.amountText, allowNegative = true)
    Column(modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            if (signed != null) MoneyFormatter.format(signed) else form.amountText,
            style = MaterialTheme.typography.headlineMedium,
        )
        Text("Reason: ${form.notes}", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Reconciliation adjustments can't be edited. If it was recorded by mistake, delete it " +
                "and reconcile the account again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


@Composable
private fun ReceiptsSection(vm: TransactionEditorViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pending by vm.pending.collectAsStateWithLifecycle()
    val saved by vm.attachments.collectAsStateWithLifecycle()
    val busy by vm.ocrBusy.collectAsStateWithLifecycle()
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.TakePicture()) { ok -> vm.onPhotoCaptured(ok) }
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()) { uri -> vm.onImagePicked(uri) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Receipts")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { camera.launch(com.wyrix.budget.data.export.ExportShare.uriFor(context, vm.newCaptureFile())) }) { Text("Take photo") }
            OutlinedButton(onClick = {
                vm.beginPicker()
                picker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Choose image") }
        }
        Row(Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            pending.forEach { img ->
                val bmp = remember(img.id) { com.wyrix.budget.data.files.ImageUtils.decode(img.bytes, 240)?.asImageBitmap() }
                Thumb(bmp, "Remove receipt photo") { vm.removePending(img.id) }
            }
            saved.forEach { a ->
                val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, a.id) { value = vm.thumbnail(a)?.asImageBitmap() }
                Thumb(bmp, "Delete saved receipt photo") { vm.removeAttachment(a) }
            }
        }
        if (pending.isNotEmpty() || saved.isNotEmpty()) {
            OutlinedButton(onClick = vm::scanReceipt, enabled = !busy) { Text(if (busy) "Reading…" else "Read receipt text") }
        }
        Text(
            "Photos are encrypted on this device. Reading the text is automatic and can be wrong, so always check the values it fills in.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Thumb(bitmap: androidx.compose.ui.graphics.ImageBitmap?, removeDescription: String, onRemove: () -> Unit) {
    Box(Modifier.size(88.dp)) {
        if (bitmap != null) androidx.compose.foundation.Image(bitmap, "Receipt photo", Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else androidx.compose.material3.CircularProgressIndicator(Modifier.align(Alignment.Center))
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd).size(32.dp)) { Icon(Icons.Filled.Close, removeDescription) }
    }
}
