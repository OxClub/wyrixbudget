package com.wyrix.budget.presentation.budget

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
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
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.calc.*
import com.wyrix.budget.domain.model.Budget
import com.wyrix.budget.domain.model.Category
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.repository.AnalyticsRepository
import com.wyrix.budget.domain.repository.BudgetRepository
import com.wyrix.budget.domain.repository.CategoryRepository
import com.wyrix.budget.domain.usecase.SaveBudgetUseCase
import com.wyrix.budget.presentation.components.*
import com.wyrix.budget.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

// ---- list ----------------------------------------------------------------------------------

data class BudgetListState(val loading: Boolean = true, val active: List<BudgetStatus> = emptyList(), val past: List<BudgetStatus> = emptyList(), val upcoming: List<BudgetStatus> = emptyList())

@HiltViewModel
class BudgetListViewModel @Inject constructor(repo: BudgetRepository) : UiViewModel() {
    private val today = LocalDate.now()
    val state: StateFlow<BudgetListState> = repo.observeStatuses(today, ZoneId.systemDefault()).map { all ->
        BudgetListState(
            loading = false,
            active = all.filter { !today.isBefore(it.budget.start) && !today.isAfter(it.budget.end) },
            upcoming = all.filter { today.isBefore(it.budget.start) },
            past = all.filter { today.isAfter(it.budget.end) },
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BudgetListState())
}

@Composable
fun BudgetScreen(onOpen: (Long) -> Unit, onAdd: () -> Unit, vm: BudgetListViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold("Budget", floatingActionButton = {
        ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New budget") })
    }) { padding ->
        when {
            s.loading -> LoadingBox(padding)
            s.active.isEmpty() && s.past.isEmpty() && s.upcoming.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No budgets yet", "Set a monthly spending limit and track it against your real expenses.", actionLabel = "Create budget", onAction = onAdd)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.active.isNotEmpty()) item { SectionTitle("Active") }
                items(s.active, key = { "a${it.budget.id}" }) { BudgetCard(it, onOpen) }
                if (s.upcoming.isNotEmpty()) item { SectionTitle("Upcoming") }
                items(s.upcoming, key = { "u${it.budget.id}" }) { BudgetCard(it, onOpen) }
                if (s.past.isNotEmpty()) item { SectionTitle("History") }
                items(s.past, key = { "p${it.budget.id}" }) { BudgetCard(it, onOpen) }
            }
        }
    }
}

@Composable
fun BudgetCard(s: BudgetStatus, onOpen: (Long) -> Unit) {
    val color = levelColor(s.level)
    InfoCard(Modifier.clickable { onOpen(s.budget.id) }) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(s.budget.name, style = MaterialTheme.typography.titleMedium)
            Text("${s.utilizationBps / 100}%", color = color, style = MaterialTheme.typography.titleMedium)
        }
        ProgressBar(s.utilizationBps / 10_000f, color, description = "${s.utilizationBps / 100} percent of budget used")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Money(s.spentMinor, style = MaterialTheme.typography.bodyMedium)
            Text("of", style = MaterialTheme.typography.bodySmall)
            Money(s.budget.totalMinor, style = MaterialTheme.typography.bodyMedium)
        }
        val tail = if (s.remainingMinor >= 0) "left" else "over"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row { Money(kotlin.math.abs(s.remainingMinor), style = MaterialTheme.typography.bodySmall, color = color); Text(" $tail", style = MaterialTheme.typography.bodySmall) }
            Text("${s.budget.start} to ${s.budget.end}" + if (s.daysRemaining > 0) "  ·  ${s.daysRemaining} days left" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- detail --------------------------------------------------------------------------------

@HiltViewModel
class BudgetDetailViewModel @Inject constructor(handle: SavedStateHandle, private val repo: BudgetRepository) : UiViewModel() {
    private val id = handle.get<Long>(Routes.ARG_ID) ?: -1L
    val status: StateFlow<BudgetStatus?> = repo.observeStatus(id, LocalDate.now(), ZoneId.systemDefault())
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    fun delete() { launchSafely { repo.delete(id); finish() } }
}

@Composable
fun BudgetDetailScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, vm: BudgetDetailViewModel = hiltViewModel()) {
    val s by vm.status.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    val baseCurrency = LocalBaseCurrency.current
    CollectDone(vm.done, onBack); CollectMessages(vm.messages, snackbar)
    ScreenScaffold(s?.budget?.name ?: "Budget", onBack, snackbar, actions = {
        s?.let { st ->
            IconButton(onClick = { onEdit(st.budget.id) }) { Icon(Icons.Filled.Edit, "Edit") }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
        }
    }) { padding ->
        val st = s
        if (st == null) { LoadingBox(padding); return@ScreenScaffold }
        val color = levelColor(st.level)
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                InfoCard {
                    Text("${st.utilizationBps / 100}% used", style = MaterialTheme.typography.headlineMedium, color = color)
                    ProgressBar(st.utilizationBps / 10_000f, color, description = "${st.utilizationBps / 100} percent used")
                    MoneyStatRow("Budget", st.budget.totalMinor)
                    MoneyStatRow("Spent", st.spentMinor)
                    MoneyStatRow(if (st.remainingMinor >= 0) "Remaining" else "Overspent by", kotlin.math.abs(st.remainingMinor), color = color)
                    StatRow("Period", "${st.budget.start} to ${st.budget.end}")
                    StatRow("Days remaining", st.daysRemaining.toString())
                    if (st.daysRemaining > 0 && st.remainingMinor > 0) MoneyStatRow("Safe to spend per day", st.remainingMinor / st.daysRemaining)
                    StatRow("Alerts at", st.budget.thresholds.joinToString(", ") { "$it%" })
                }
            }
            if (st.categories.isNotEmpty()) item { SectionTitle("Categories") }
            items(st.categories, key = { it.categoryId }) { c ->
                val cc = levelColor(c.level)
                InfoCard {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(c.name, style = MaterialTheme.typography.titleSmall)
                        Text("${c.utilizationBps / 100}%", color = cc)
                    }
                    ProgressBar(c.utilizationBps / 10_000f, cc)
                    MoneyStatRow("Spent of allocation", c.spentMinor)
                    MoneyStatRow("Allocated", c.allocatedMinor)
                    MoneyStatRow(if (c.remainingMinor >= 0) "Remaining" else "Over by", kotlin.math.abs(c.remainingMinor), color = cc)
                }
            }
            if (st.unallocatedSpentMinor > 0) item { Note("Spending in categories without an allocation: ${MoneyFormatter.format(st.unallocatedSpentMinor, baseCurrency)}. It still counts toward the total.") }
            if (st.budget.countOnlyAllocated) item { Note("This is a category budget: only spending in the categories above counts toward it.") }
            item { Note("Spent = expenses recorded in this period. Transfers and reconciliation adjustments are never counted.") }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this budget?", "Your transactions are not affected. This removes only the budget and its history.", "Delete", vm::delete) { confirmDelete = false }
}


// ---- editor --------------------------------------------------------------------------------

data class AllocationRowUi(val categoryId: Long, val name: String, val amountText: String)

data class BudgetForm(
    val name: String = "",
    val periodType: BudgetPeriodType = BudgetPeriodType.MONTHLY,
    val start: LocalDate = LocalDate.now().withDayOfMonth(1),
    val end: LocalDate = LocalDate.now().withDayOfMonth(1).plusMonths(1).minusDays(1),
    val totalText: String = "",
    val thresholds: Set<Int> = BudgetCalculator.DEFAULT_THRESHOLDS.toSet(),
    val countOnlyAllocated: Boolean = false,
    val rows: List<AllocationRowUi> = emptyList(),
    val errors: List<String> = emptyList(),
    val saving: Boolean = false,
)

@HiltViewModel
class BudgetEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: BudgetRepository,
    categoryRepo: CategoryRepository,
    private val analytics: AnalyticsRepository,
    private val saveBudget: SaveBudgetUseCase,
    private val prefs: UserPreferencesRepository,
    private val clock: Clock,
) : UiViewModel() {
    private val editingId: Long? = (handle.get<Long>(Routes.ARG_ID) ?: -1L).takeIf { it > 0 }
    private var createdAt = 0L
    val isEditing = editingId != null
    private val _form = MutableStateFlow(BudgetForm())
    val form: StateFlow<BudgetForm> = _form
    val categories: StateFlow<List<Category>> = categoryRepo.observeByKind(CategoryKind.EXPENSE).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var saving = false

    init {
        editingId?.let { id ->
            launchSafely {
                val (b, allocs) = repo.get(id) ?: return@launchSafely
                createdAt = b.createdAt
                val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
                _form.value = BudgetForm(b.name, b.periodType, b.startDate, b.endDate, MoneyFormatter.formatPlain(b.totalMinor, cur), b.thresholds.toSet(), b.countOnlyAllocated,
                    allocs.map { AllocationRowUi(it.categoryId, it.name, MoneyFormatter.formatPlain(it.amountMinor, cur)) })
            }
        }
    }

    fun update(block: (BudgetForm) -> BudgetForm) = _form.update { block(it).copy(errors = emptyList()) }

    fun onPeriodType(t: BudgetPeriodType) = update {
        if (t == BudgetPeriodType.CUSTOM) it.copy(periodType = t) else { val (s, e) = BudgetCalculator.periodFor(t, LocalDate.now()); it.copy(periodType = t, start = s, end = e) }
    }

    fun addCategory(c: Category) = update { f -> if (f.rows.any { it.categoryId == c.id }) f else f.copy(rows = f.rows + AllocationRowUi(c.id, c.name, "")) }
    fun removeRow(id: Long) = update { f -> f.copy(rows = f.rows.filter { it.categoryId != id }) }
    fun setAmount(id: Long, text: String) = update { f -> f.copy(rows = f.rows.map { if (it.categoryId == id) it.copy(amountText = text) else it }) }

    /** Fills allocations from the last 90 days of real spending, scaled to the total with exact rounding. */
    fun suggestFromHistory() {
        launchSafely {
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            val total = (MoneyParser.parse(_form.value.totalText, currency = cur) as? MoneyParseResult.Valid)?.minor
            if (total == null || total <= 0) { say("Enter the total budget first."); return@launchSafely }
            val now = clock.instant()
            val range = com.wyrix.budget.core.time.TimeRange(now.minusSeconds(90L * 86_400).toEpochMilli(), now.plusSeconds(86_400).toEpochMilli())
            val top = analytics.byCategory(true, range).filter { it.amountMinor > 0 }.take(8)
            if (top.isEmpty()) { say("No spending history yet to base a suggestion on."); return@launchSafely }
            val parts = BudgetAllocator.allocate(total, top.map { (it.amountMinor / 100).toInt().coerceAtLeast(1) })
            _form.update { f -> f.copy(rows = top.mapIndexed { i, c -> AllocationRowUi(c.categoryId, c.name, MoneyFormatter.formatPlain(parts[i], cur)) }, errors = emptyList()) }
            say("Suggested from your last 90 days of spending. Adjust as you like.")
        }
    }

    fun save() {
        if (saving) return
        saving = true
        launchSafely {
            try {
                val f = _form.value
                val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
                val total = (MoneyParser.parse(f.totalText, currency = cur) as? MoneyParseResult.Valid)?.minor
                if (total == null) { _form.update { it.copy(errors = listOf("Enter a valid total budget.")) }; return@launchSafely }
                val allocs = ArrayList<AllocationInput>()
                for (r in f.rows) {
                    val m = (MoneyParser.parse(r.amountText, currency = cur) as? MoneyParseResult.Valid)?.minor
                    if (m == null) { _form.update { it.copy(errors = listOf("Enter an amount for ${r.name}.")) }; return@launchSafely }
                    allocs += AllocationInput(r.categoryId, r.name, m)
                }
                val budget = Budget(editingId ?: 0, f.name, f.periodType, f.start, f.end, total, f.thresholds.toList().sorted(), f.countOnlyAllocated, createdAt)
                handle(saveBudget(budget, allocs)) { finish() }
                _form.update { it.copy(errors = emptyList()) }
            } finally { saving = false }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetEditorScreen(onBack: () -> Unit, vm: BudgetEditorViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var pickCategory by remember { mutableStateOf(false) }
    CollectDone(vm.done, onBack); CollectMessages(vm.messages, snackbar)
    ScreenScaffold(if (vm.isEditing) "Edit budget" else "New budget", onBack, snackbar) { padding ->
        FormColumn(padding) {
            AppTextField(f.name, { v -> vm.update { it.copy(name = v.take(40)) } }, "Budget name")
            ChipSelect("Period", BudgetPeriodType.entries, f.periodType, vm::onPeriodType) { it.label }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DatePickerField(f.start, { d -> vm.update { it.copy(start = d, periodType = BudgetPeriodType.CUSTOM) } }, Modifier.weight(1f))
                DatePickerField(f.end, { d -> vm.update { it.copy(end = d, periodType = BudgetPeriodType.CUSTOM) } }, Modifier.weight(1f))
            }
            MoneyField(f.totalText, { v -> vm.update { it.copy(totalText = v) } }, "Total budget")
            SectionLabel("Alert me at")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(50, 75, 90, 100).forEach { t ->
                    FilterChip(selected = t in f.thresholds, onClick = { vm.update { it.copy(thresholds = if (t in it.thresholds) it.thresholds - t else it.thresholds + t) } }, label = { Text("$t%") })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Category budget")
                    Note("Count only spending in the categories below. Off = all expenses count.")
                }
                Switch(checked = f.countOnlyAllocated, onCheckedChange = { v -> vm.update { it.copy(countOnlyAllocated = v) } })
            }
            SectionTitle("Category amounts (optional)")
            f.rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { MoneyField(r.amountText, { v -> vm.setAmount(r.categoryId, v) }, r.name) }
                    IconButton(onClick = { vm.removeRow(r.categoryId) }) { Icon(Icons.Filled.Close, "Remove ${r.name}") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickCategory = true }) { Icon(Icons.Filled.Add, null); Text("Add category") }
                OutlinedButton(onClick = vm::suggestFromHistory) { Icon(Icons.Filled.AutoAwesome, null); Text("Suggest") }
            }
            ErrorList(f.errors)
            Button(onClick = vm::save, Modifier.fillMaxWidth()) { Text("Save budget") }
        }
    }
    if (pickCategory) {
        AlertDialog(onDismissRequest = { pickCategory = false }, title = { Text("Add a category") }, confirmButton = {
            TextButton(onClick = { pickCategory = false }) { Text("Done") }
        }, text = {
            LazyColumn { items(categories.filter { c -> f.rows.none { it.categoryId == c.id } }, key = { it.id }) { c ->
                Text(c.name, Modifier.fillMaxWidth().clickable { vm.addCategory(c) }.padding(vertical = 12.dp))
            } }
        })
    }
}
