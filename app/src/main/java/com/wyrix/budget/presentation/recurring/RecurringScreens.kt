package com.wyrix.budget.presentation.recurring

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
import com.wyrix.budget.domain.calc.Recurrence
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.CategoryRepository
import com.wyrix.budget.domain.repository.RecurringRepository
import com.wyrix.budget.domain.usecase.HandleRecurringUseCase
import com.wyrix.budget.domain.usecase.SaveRecurringRuleUseCase
import com.wyrix.budget.presentation.components.*
import com.wyrix.budget.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import javax.inject.Inject

data class PendingItem(val rule: RecurringRule, val dueDate: LocalDate, val moreCount: Int)

@HiltViewModel
class RecurringViewModel @Inject constructor(repo: RecurringRepository, private val handler: HandleRecurringUseCase) : UiViewModel() {
    val rules: StateFlow<List<RecurringRule>?> = repo.observeRules().map<List<RecurringRule>, List<RecurringRule>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun pending(rules: List<RecurringRule>, today: LocalDate): List<PendingItem> = rules.filter { it.isActive && !it.nextDate.isAfter(today) }.map { r ->
        val due = Recurrence.between(r.startDate, r.frequency, r.customDays, r.nextDate, today, limit = 60)
        PendingItem(r, r.nextDate, (due.size - 1).coerceAtLeast(0))
    }

    fun confirm(p: PendingItem) { launchSafely { handle(handler.confirm(p.rule.id, p.dueDate)) { say("Recorded.") } } }
    fun skip(p: PendingItem) { launchSafely { handle(handler.skip(p.rule.id, p.dueDate)) { say("Skipped.") } } }
}

@Composable
fun RecurringScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onAdd: () -> Unit, vm: RecurringViewModel = hiltViewModel()) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    ScreenScaffold("Recurring", onBack, snackbar, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add") })
    }) { padding ->
        val list = rules
        when {
            list == null -> LoadingBox(padding)
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("Nothing recurring yet", "Add your salary, rent or other regular items. Budget asks you to confirm each one, so nothing is recorded without you.", actionLabel = "Add recurring item", onAction = onAdd)
            }
            else -> {
                val pending = vm.pending(list, LocalDate.now())
                LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (pending.isNotEmpty()) item { SectionTitle("Waiting for you") }
                    items(pending, key = { "p${it.rule.id}" }) { p ->
                        InfoCard {
                            Text(p.rule.title, style = MaterialTheme.typography.titleMedium)
                            Row { Money(p.rule.amountMinor, prefix = if (p.rule.type == TransactionType.INCOME) "+" else "-"); Text("  ·  due ${p.dueDate}", style = MaterialTheme.typography.bodyMedium) }
                            if (p.moreCount > 0) Note("${p.moreCount} more earlier-dated occurrence${if (p.moreCount == 1) "" else "s"} will follow after this one.")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { vm.confirm(p) }) { Text("Record it") }
                                OutlinedButton(onClick = { vm.skip(p) }) { Text("Skip") }
                            }
                        }
                    }
                    item { SectionTitle("All recurring items") }
                    items(list, key = { it.id }) { r ->
                        InfoCard(Modifier.clickable { onOpen(r.id) }) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(r.title, style = MaterialTheme.typography.titleSmall)
                                Money(r.amountMinor, prefix = if (r.type == TransactionType.INCOME) "+" else "-")
                            }
                            Note("${r.frequency.label}${if (r.isActive) " · next ${r.nextDate}" else " · inactive"}")
                        }
                    }
                }
            }
        }
    }
}

data class RuleForm(
    val title: String = "", val type: TransactionType = TransactionType.EXPENSE, val amountText: String = "", val categoryId: Long? = null, val accountId: Long? = null,
    val frequency: Frequency = Frequency.MONTHLY, val customDaysText: String = "30", val start: LocalDate = LocalDate.now(), val hasEnd: Boolean = false,
    val end: LocalDate = LocalDate.now().plusYears(1), val active: Boolean = true, val errors: List<String> = emptyList(),
)

@HiltViewModel
class RecurringEditorViewModel @Inject constructor(
    handle: SavedStateHandle, private val repo: RecurringRepository, private val save: SaveRecurringRuleUseCase,
    accountRepo: AccountRepository, private val categoryRepo: CategoryRepository, private val prefs: UserPreferencesRepository,
) : UiViewModel() {
    private val id: Long? = (handle.get<Long>(Routes.ARG_ID) ?: -1L).takeIf { it > 0 }
    val isEditing = id != null
    private var original: RecurringRule? = null
    private val _form = MutableStateFlow(RuleForm())
    val form: StateFlow<RuleForm> = _form
    val accounts = accountRepo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val categories = _form.map { it.type }.distinctUntilChanged().flatMapLatest { categoryRepo.observeByKind(if (it == TransactionType.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var saving = false

    init {
        id?.let { rid -> launchSafely {
            val r = repo.get(rid) ?: return@launchSafely
            original = r
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            _form.value = RuleForm(r.title, r.type, MoneyFormatter.formatPlain(r.amountMinor, cur), r.categoryId, r.accountId, r.frequency, r.customDays.toString(), r.nextDate, r.endDate != null, r.endDate ?: LocalDate.now().plusYears(1), r.isActive)
        } }
    }

    fun update(block: (RuleForm) -> RuleForm) = _form.update { block(it).copy(errors = emptyList()) }

    fun submit() {
        if (saving) return
        saving = true
        launchSafely { try {
            val f = _form.value
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            val amount = (MoneyParser.parse(f.amountText, currency = cur) as? MoneyParseResult.Valid)?.minor
            if (amount == null) { _form.update { it.copy(errors = listOf("Enter a valid amount.")) }; return@launchSafely }
            val cat = f.categoryId; val acc = f.accountId
            if (cat == null || acc == null) { _form.update { it.copy(errors = listOf("Choose a category and an account.")) }; return@launchSafely }
            val o = original
            val anchor = if (o != null && o.nextDate == f.start) o.startDate else f.start
            val rule = RecurringRule(id ?: 0, f.title, f.type, amount, cat, acc, f.frequency, f.customDaysText.toIntOrNull() ?: 0, anchor, f.start, if (f.hasEnd) f.end else null, f.active, o?.createdAt ?: 0)
            when (val r = save(rule)) { is ActionResult.Success -> finish(); is ActionResult.Rejected -> _form.update { it.copy(errors = r.messages) } }
        } finally { saving = false } }
    }

    fun delete() { val rid = id ?: return; launchSafely { repo.delete(rid); finish() } }
}

@Composable
fun RecurringEditorScreen(onBack: () -> Unit, vm: RecurringEditorViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    CollectDone(vm.done, onBack); CollectMessages(vm.messages, snackbar)
    ScreenScaffold(if (vm.isEditing) "Edit recurring item" else "Add recurring item", onBack, snackbar, actions = {
        if (vm.isEditing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
    }) { padding ->
        FormColumn(padding) {
            AppTextField(f.title, { v -> vm.update { it.copy(title = v.take(60)) } }, "Title")
            ChipSelect("Type", listOf(TransactionType.EXPENSE, TransactionType.INCOME), f.type, { t -> vm.update { it.copy(type = t, categoryId = null) } }) { if (it == TransactionType.INCOME) "Income" else "Expense" }
            MoneyField(f.amountText, { v -> vm.update { it.copy(amountText = v) } }, "Amount")
            CategoryChips("Category", categories, f.categoryId, { c -> vm.update { it.copy(categoryId = c) } }, optional = false)
            AccountChips("Account", accounts, f.accountId, { a -> vm.update { it.copy(accountId = a) } }, optional = false)
            ChipSelect("Repeats", Frequency.entries.filter { it != Frequency.ONCE }, f.frequency, { x -> vm.update { it.copy(frequency = x) } }) { it.label }
            if (f.frequency == Frequency.CUSTOM) NumberField(f.customDaysText, { v -> vm.update { it.copy(customDaysText = v) } }, "Every how many days")
            SectionLabel(if (vm.isEditing) "Next occurrence" else "First occurrence"); DatePickerField(f.start, { d -> vm.update { it.copy(start = d) } })
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Has an end date", Modifier.weight(1f)); Switch(f.hasEnd, { v -> vm.update { it.copy(hasEnd = v) } }) }
            if (f.hasEnd) DatePickerField(f.end, { d -> vm.update { it.copy(end = d) } })
            if (vm.isEditing) Row(verticalAlignment = Alignment.CenterVertically) { Text("Active", Modifier.weight(1f)); Switch(f.active, { v -> vm.update { it.copy(active = v) } }) }
            Note("Nothing is recorded automatically. When an occurrence is due you'll be asked to confirm or skip it.")
            ErrorList(f.errors)
            Button(vm::submit, Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this recurring item?", "Transactions already recorded from it are kept.", "Delete", vm::delete) { confirmDelete = false }
}
