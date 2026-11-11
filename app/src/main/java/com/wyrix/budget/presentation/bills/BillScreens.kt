package com.wyrix.budget.presentation.bills

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.money.MoneyParseResult
import com.wyrix.budget.core.money.MoneyParser
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.calc.Frequency
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.BillRepository
import com.wyrix.budget.domain.repository.CategoryRepository
import com.wyrix.budget.domain.usecase.PayBillUseCase
import com.wyrix.budget.domain.usecase.SaveBillUseCase
import com.wyrix.budget.presentation.components.*
import com.wyrix.budget.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class BillsViewModel @Inject constructor(
    repo: BillRepository, accountRepo: AccountRepository, private val payBill: PayBillUseCase,
) : UiViewModel() {
    val bills: StateFlow<List<Bill>?> = repo.observeBills().map<List<Bill>, List<Bill>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val accounts: StateFlow<List<AccountWithBalance>> = accountRepo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pay(billId: Long, record: Boolean, accountId: Long?) {
        launchSafely { handle(payBill(billId, record, accountId, LocalDate.now())) { say("Marked as paid.") } }
    }
}

@Composable
fun BillsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onAdd: () -> Unit, vm: BillsViewModel = hiltViewModel()) {
    val bills by vm.bills.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var paying by remember { mutableStateOf<Bill?>(null) }
    CollectMessages(vm.messages, snackbar)
    val today = LocalDate.now()
    ScreenScaffold("Bills", onBack, snackbar, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add bill") })
    }) { padding ->
        val list = bills
        when {
            list == null -> LoadingBox(padding)
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No bills yet", "Add rent, utilities or any payment that repeats, and get reminders before it's due.", actionLabel = "Add bill", onAction = onAdd)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val next30 = list.filter { it.isActive && !it.nextDueDate.isAfter(today.plusDays(30)) }.sumOf { it.amountMinor }
                item { InfoCard { MoneyStatRow("Due in the next 30 days", next30); StatRow("Overdue", list.count { it.status(today) == BillStatus.OVERDUE }.toString()) } }
                items(list, key = { it.id }) { b ->
                    val st = b.status(today)
                    val color = when (st) { BillStatus.OVERDUE -> MaterialTheme.colorScheme.error; BillStatus.DUE_SOON -> MaterialTheme.colorScheme.tertiary; BillStatus.PAID -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant }
                    InfoCard(Modifier.clickable { onOpen(b.id) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(b.name, style = MaterialTheme.typography.titleMedium)
                            Text(st.label, color = color, style = MaterialTheme.typography.labelLarge)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Money(b.amountMinor, style = MaterialTheme.typography.titleSmall)
                                Note(if (b.isActive) "Due ${b.nextDueDate} · ${b.frequency.label}" else "Paid · ${b.frequency.label}")
                            }
                            if (b.isActive) FilledTonalButton(onClick = { paying = b }) { Text("Mark paid") }
                        }
                    }
                }
            }
        }
    }
    paying?.let { b ->
        var record by remember { mutableStateOf(b.categoryId != null) }
        var account by remember { mutableStateOf(b.accountId ?: accounts.firstOrNull()?.account?.id) }
        AlertDialog(onDismissRequest = { paying = null }, title = { Text("Mark ${b.name} paid") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Due ${b.nextDueDate}. The bill will move to its next due date.")
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Also record as an expense", Modifier.weight(1f)); Switch(record, { record = it }) }
                if (record) {
                    if (b.categoryId == null) Text("This bill has no category. Edit it to add one, or turn this off.", color = MaterialTheme.colorScheme.error)
                    AccountChips("Paid from", accounts, account, { account = it }, optional = false)
                }
            }
        }, confirmButton = { TextButton(onClick = { vm.pay(b.id, record, account); paying = null }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { paying = null }) { Text("Cancel") } })
    }
}

// ---- editor --------------------------------------------------------------------------------

data class BillForm(
    val name: String = "", val amountText: String = "", val categoryId: Long? = null, val accountId: Long? = null,
    val due: LocalDate = LocalDate.now().plusDays(7), val frequency: Frequency = Frequency.MONTHLY,
    val reminders: List<Int> = listOf(3, 1, 0), val active: Boolean = true, val errors: List<String> = emptyList(),
)

@HiltViewModel
class BillEditorViewModel @Inject constructor(
    handle: SavedStateHandle, private val repo: BillRepository, private val save: SaveBillUseCase,
    accountRepo: AccountRepository, categoryRepo: CategoryRepository, private val prefs: UserPreferencesRepository,
) : UiViewModel() {
    private val id: Long? = (handle.get<Long>(Routes.ARG_ID) ?: -1L).takeIf { it > 0 }
    val isEditing = id != null
    private var createdAt = 0L
    private var originalDue: LocalDate? = null
    private var originalAnchor: LocalDate? = null
    private val _form = MutableStateFlow(BillForm())
    val form: StateFlow<BillForm> = _form
    val accounts = accountRepo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = categoryRepo.observeByKind(CategoryKind.EXPENSE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val payments: StateFlow<List<BillPayment>> = (id?.let { repo.observePayments(it) } ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var saving = false

    init {
        id?.let { bid -> launchSafely {
            val b = repo.get(bid) ?: return@launchSafely
            createdAt = b.createdAt; originalDue = b.nextDueDate; originalAnchor = b.startDate
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            _form.value = BillForm(b.name, MoneyFormatter.formatPlain(b.amountMinor, cur), b.categoryId, b.accountId, b.nextDueDate, b.frequency, b.reminderDays, b.isActive)
        } }
    }

    fun update(block: (BillForm) -> BillForm) = _form.update { block(it).copy(errors = emptyList()) }

    fun submit() {
        if (saving) return
        saving = true
        launchSafely { try {
            val f = _form.value
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            val amount = (MoneyParser.parse(f.amountText, currency = cur) as? MoneyParseResult.Valid)?.minor
            if (amount == null) { _form.update { it.copy(errors = listOf("Enter a valid amount.")) }; return@launchSafely }
            // The recurrence anchor only moves when the user changes the due date, so month-end bills don't drift.
            val anchor = if (originalDue == f.due && originalAnchor != null) originalAnchor!! else f.due
            val bill = Bill(id ?: 0, f.name, amount, f.categoryId, f.accountId, anchor, f.due, f.frequency, f.reminders, f.active, createdAt)
            when (val r = save(bill)) { is ActionResult.Success -> finish(); is ActionResult.Rejected -> _form.update { it.copy(errors = r.messages) } }
        } finally { saving = false } }
    }

    fun delete() { val bid = id ?: return; launchSafely { repo.delete(bid); finish() } }
}

@Composable
fun BillEditorScreen(onBack: () -> Unit, vm: BillEditorViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    CollectDone(vm.done, onBack); CollectMessages(vm.messages, snackbar)
    ScreenScaffold(if (vm.isEditing) "Edit bill" else "Add bill", onBack, snackbar, actions = {
        if (vm.isEditing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
    }) { padding ->
        FormColumn(padding) {
            AppTextField(f.name, { v -> vm.update { it.copy(name = v.take(60)) } }, "Bill name")
            MoneyField(f.amountText, { v -> vm.update { it.copy(amountText = v) } }, "Amount")
            SectionLabel("Next due date")
            DatePickerField(f.due, { d -> vm.update { it.copy(due = d) } })
            ChipSelect("Repeats", listOf(Frequency.ONCE, Frequency.WEEKLY, Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.HALF_YEARLY, Frequency.YEARLY), f.frequency, { x -> vm.update { it.copy(frequency = x) } }) { it.label }
            CategoryChips("Category (needed to record expenses)", categories, f.categoryId, { c -> vm.update { it.copy(categoryId = c) } })
            AccountChips("Usually paid from", accounts, f.accountId, { a -> vm.update { it.copy(accountId = a) } })
            ReminderChips(f.reminders, { r -> vm.update { it.copy(reminders = r) } })
            if (vm.isEditing) Row(verticalAlignment = Alignment.CenterVertically) { Text("Active", Modifier.weight(1f)); Switch(f.active, { v -> vm.update { it.copy(active = v) } }) }
            ErrorList(f.errors)
            Button(vm::submit, Modifier.fillMaxWidth()) { Text("Save bill") }
            if (vm.isEditing) {
                SectionTitle("Payment history")
                if (payments.isEmpty()) Note("No payments recorded yet.")
                payments.forEach { p -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Due ${p.dueDate}"); Money(p.amountMinor) } }
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this bill?", "Its payment history is deleted. Expenses you already recorded stay in your transactions.", "Delete", vm::delete) { confirmDelete = false }
}
