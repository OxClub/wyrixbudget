package com.wyrix.budget.presentation.subscriptions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.wyrix.budget.domain.repository.CategoryRepository
import com.wyrix.budget.domain.repository.SubscriptionRepository
import com.wyrix.budget.domain.usecase.RenewSubscriptionUseCase
import com.wyrix.budget.domain.usecase.SaveSubscriptionUseCase
import com.wyrix.budget.presentation.components.*
import com.wyrix.budget.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class SubscriptionsViewModel @Inject constructor(
    repo: SubscriptionRepository, accountRepo: AccountRepository, private val renew: RenewSubscriptionUseCase,
) : UiViewModel() {
    val subs: StateFlow<List<Subscription>?> = repo.observeAll().map<List<Subscription>, List<Subscription>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val accounts = accountRepo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun renew(id: Long, record: Boolean, accountId: Long?) { launchSafely { handle(renew(id, record, accountId, LocalDate.now())) { say("Renewal recorded.") } } }
}

@Composable
fun SubscriptionsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onAdd: () -> Unit, vm: SubscriptionsViewModel = hiltViewModel()) {
    val subs by vm.subs.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var renewing by remember { mutableStateOf<Subscription?>(null) }
    val baseCur = LocalBaseCurrency.current
    CollectMessages(vm.messages, snackbar)
    ScreenScaffold("Subscriptions", onBack, snackbar, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") })
    }) { padding ->
        val list = subs
        when {
            list == null -> LoadingBox(padding)
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No subscriptions", "Track streaming, apps and memberships to see what they really cost.", actionLabel = "Add subscription", onAction = onAdd)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val active = list.filter { it.status == SubscriptionStatus.ACTIVE }
                item {
                    InfoCard {
                        MoneyStatRow("Monthly cost", active.sumOf { it.monthlyCostMinor })
                        MoneyStatRow("Annual cost", active.sumOf { it.annualCostMinor })
                        StatRow("Active subscriptions", active.size.toString())
                    }
                }
                items(list, key = { it.id }) { s ->
                    InfoCard(Modifier.clickable { onOpen(s.id) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s.name, style = MaterialTheme.typography.titleMedium)
                            Text(s.status.label, style = MaterialTheme.typography.labelMedium)
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Money(s.amountMinor, style = MaterialTheme.typography.titleSmall)
                                Note("${s.cycle.label} · renews ${s.renewalDate} · ≈ ${fmt(s.monthlyCostMinor, baseCur)}/month")
                            }
                            if (s.status == SubscriptionStatus.ACTIVE) FilledTonalButton(onClick = { renewing = s }) { Text("Renewed") }
                        }
                    }
                }
            }
        }
    }
    renewing?.let { s ->
        var record by remember { mutableStateOf(s.categoryId != null) }
        var account by remember { mutableStateOf(s.accountId ?: accounts.firstOrNull()?.account?.id) }
        AlertDialog(onDismissRequest = { renewing = null }, title = { Text("${s.name} renewed") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Renewal due ${s.renewalDate}. It will move to the next renewal date.")
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Also record as an expense", Modifier.weight(1f)); Switch(record, { record = it }) }
                if (record) {
                    if (s.categoryId == null) Text("Add a category to this subscription first, or turn this off.", color = MaterialTheme.colorScheme.error)
                    AccountChips("Paid from", accounts, account, { account = it }, optional = false)
                }
            }
        }, confirmButton = { TextButton(onClick = { vm.renew(s.id, record, account); renewing = null }) { Text("Confirm") } },
            dismissButton = { TextButton(onClick = { renewing = null }) { Text("Cancel") } })
    }
}

data class SubForm(
    val name: String = "", val amountText: String = "", val cycle: Frequency = Frequency.MONTHLY,
    val renewal: LocalDate = LocalDate.now().plusMonths(1), val status: SubscriptionStatus = SubscriptionStatus.ACTIVE,
    val accountId: Long? = null, val categoryId: Long? = null, val reminders: List<Int> = listOf(3, 1), val errors: List<String> = emptyList(),
)

@HiltViewModel
class SubscriptionEditorViewModel @Inject constructor(
    handle: SavedStateHandle, private val repo: SubscriptionRepository, private val save: SaveSubscriptionUseCase,
    accountRepo: AccountRepository, categoryRepo: CategoryRepository, private val prefs: UserPreferencesRepository,
) : UiViewModel() {
    private val id: Long? = (handle.get<Long>(Routes.ARG_ID) ?: -1L).takeIf { it > 0 }
    val isEditing = id != null
    private var createdAt = 0L
    private var originalRenewal: LocalDate? = null
    private var originalAnchor: LocalDate? = null
    private val _form = MutableStateFlow(SubForm())
    val form: StateFlow<SubForm> = _form
    val accounts = accountRepo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = categoryRepo.observeByKind(CategoryKind.EXPENSE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val payments: StateFlow<List<SubscriptionPayment>> = (id?.let { repo.observePayments(it) } ?: flowOf(emptyList())).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var saving = false

    init {
        id?.let { sid -> launchSafely {
            val s = repo.get(sid) ?: return@launchSafely
            createdAt = s.createdAt; originalRenewal = s.renewalDate; originalAnchor = s.startDate
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            _form.value = SubForm(s.name, MoneyFormatter.formatPlain(s.amountMinor, cur), s.cycle, s.renewalDate, s.status, s.accountId, s.categoryId, s.reminderDays)
        } }
    }

    fun update(block: (SubForm) -> SubForm) = _form.update { block(it).copy(errors = emptyList()) }

    fun submit() {
        if (saving) return
        saving = true
        launchSafely { try {
            val f = _form.value
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            val amount = (MoneyParser.parse(f.amountText, currency = cur) as? MoneyParseResult.Valid)?.minor
            if (amount == null) { _form.update { it.copy(errors = listOf("Enter a valid amount.")) }; return@launchSafely }
            val anchor = if (originalRenewal == f.renewal && originalAnchor != null) originalAnchor!! else f.renewal
            val sub = Subscription(id ?: 0, f.name, amount, f.cycle, anchor, f.renewal, f.accountId, f.categoryId, f.status, f.reminders, createdAt)
            when (val r = save(sub)) { is ActionResult.Success -> finish(); is ActionResult.Rejected -> _form.update { it.copy(errors = r.messages) } }
        } finally { saving = false } }
    }

    fun delete() { val sid = id ?: return; launchSafely { repo.delete(sid); finish() } }
}

@Composable
fun SubscriptionEditorScreen(onBack: () -> Unit, vm: SubscriptionEditorViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    CollectDone(vm.done, onBack); CollectMessages(vm.messages, snackbar)
    ScreenScaffold(if (vm.isEditing) "Edit subscription" else "Add subscription", onBack, snackbar, actions = {
        if (vm.isEditing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
    }) { padding ->
        FormColumn(padding) {
            AppTextField(f.name, { v -> vm.update { it.copy(name = v.take(60)) } }, "Name")
            MoneyField(f.amountText, { v -> vm.update { it.copy(amountText = v) } }, "Amount per cycle")
            ChipSelect("Billing cycle", listOf(Frequency.WEEKLY, Frequency.MONTHLY, Frequency.QUARTERLY, Frequency.HALF_YEARLY, Frequency.YEARLY), f.cycle, { x -> vm.update { it.copy(cycle = x) } }) { it.label }
            SectionLabel("Next renewal date")
            DatePickerField(f.renewal, { d -> vm.update { it.copy(renewal = d) } })
            ChipSelect("Status", SubscriptionStatus.entries, f.status, { s -> vm.update { it.copy(status = s) } }) { it.label }
            CategoryChips("Category (needed to record expenses)", categories, f.categoryId, { c -> vm.update { it.copy(categoryId = c) } })
            AccountChips("Paid from", accounts, f.accountId, { a -> vm.update { it.copy(accountId = a) } })
            ReminderChips(f.reminders, { r -> vm.update { it.copy(reminders = r) } }, listOf(7, 3, 1))
            ErrorList(f.errors)
            Button(vm::submit, Modifier.fillMaxWidth()) { Text("Save subscription") }
            if (vm.isEditing) {
                SectionTitle("Renewal history")
                if (payments.isEmpty()) Note("No renewals recorded yet.")
                payments.forEach { p -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Renewed ${p.dueDate}"); Money(p.amountMinor) } }
                if (payments.isNotEmpty()) MoneyStatRow("Total paid", payments.sumOf { it.amountMinor })
            }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this subscription?", "Its renewal history is deleted. Expenses you already recorded stay in your transactions.", "Delete", vm::delete) { confirmDelete = false }
}
