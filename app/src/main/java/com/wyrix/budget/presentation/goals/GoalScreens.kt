package com.wyrix.budget.presentation.goals

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.calc.SavingsMath
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.GoalRepository
import com.wyrix.budget.domain.usecase.AddContributionUseCase
import com.wyrix.budget.domain.usecase.SaveGoalUseCase
import com.wyrix.budget.presentation.components.*
import com.wyrix.budget.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

@HiltViewModel
class GoalsViewModel @Inject constructor(repo: GoalRepository) : UiViewModel() {
    val goals: StateFlow<List<GoalWithSaved>?> = repo.observeGoals().map<List<GoalWithSaved>, List<GoalWithSaved>?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun GoalsScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onAdd: () -> Unit, vm: GoalsViewModel = hiltViewModel()) {
    val goals by vm.goals.collectAsStateWithLifecycle()
    ScreenScaffold("Savings goals", onBack, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("New goal") })
    }) { padding ->
        val list = goals
        when {
            list == null -> LoadingBox(padding)
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No savings goals", "Save toward something specific, like an emergency fund or a trip.", actionLabel = "Create goal", onAction = onAdd)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(list, key = { it.goal.id }) { g ->
                    val bps = SavingsMath.progressBps(g.savedMinor, g.goal.targetMinor)
                    InfoCard(Modifier.clickable { onOpen(g.goal.id) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(g.goal.name, style = MaterialTheme.typography.titleMedium)
                            Text(g.goal.status.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        ProgressBar(bps / 10_000f, MaterialTheme.colorScheme.primary, description = "${bps / 100} percent saved")
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Money(g.savedMinor, style = MaterialTheme.typography.bodyMedium)
                            Text("${bps / 100}%  of  ", style = MaterialTheme.typography.bodySmall)
                            Money(g.goal.targetMinor, style = MaterialTheme.typography.bodyMedium)
                        }
                        g.goal.targetDate?.let { Note("Target date $it · ${g.goal.priority.label} priority") }
                    }
                }
            }
        }
    }
}

// ---- editor --------------------------------------------------------------------------------

data class GoalForm(
    val name: String = "", val targetText: String = "", val hasDate: Boolean = false,
    val date: LocalDate = LocalDate.now().plusMonths(12), val priority: GoalPriority = GoalPriority.MEDIUM,
    val description: String = "", val status: GoalStatus = GoalStatus.ACTIVE, val errors: List<String> = emptyList(),
)

@HiltViewModel
class GoalEditorViewModel @Inject constructor(
    handle: SavedStateHandle, private val repo: GoalRepository, private val save: SaveGoalUseCase, private val prefs: UserPreferencesRepository,
) : UiViewModel() {
    private val id: Long? = (handle.get<Long>(Routes.ARG_ID) ?: -1L).takeIf { it > 0 }
    val isEditing = id != null
    private var createdAt = 0L
    private val _form = MutableStateFlow(GoalForm())
    val form: StateFlow<GoalForm> = _form
    private var saving = false

    init {
        id?.let { gid -> launchSafely {
            val g = repo.observeGoal(gid).first()?.goal ?: return@launchSafely
            createdAt = g.createdAt
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            _form.value = GoalForm(g.name, MoneyFormatter.formatPlain(g.targetMinor, cur), g.targetDate != null, g.targetDate ?: LocalDate.now().plusMonths(12), g.priority, g.description, g.status)
        } }
    }

    fun update(block: (GoalForm) -> GoalForm) = _form.update { block(it).copy(errors = emptyList()) }

    fun submit() {
        if (saving) return
        saving = true
        launchSafely { try {
            val f = _form.value
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            val target = (MoneyParser.parse(f.targetText, currency = cur) as? MoneyParseResult.Valid)?.minor
            if (target == null) { _form.update { it.copy(errors = listOf("Enter a valid target amount.")) }; return@launchSafely }
            when (val r = save(SavingsGoal(id ?: 0, f.name, target, if (f.hasDate) f.date else null, f.priority, f.description, f.status, createdAt))) {
                is ActionResult.Success -> finish()
                is ActionResult.Rejected -> _form.update { it.copy(errors = r.messages) }
            }
        } finally { saving = false } }
    }
}

@Composable
fun GoalEditorScreen(onBack: () -> Unit, vm: GoalEditorViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectDone(vm.done, onBack); CollectMessages(vm.messages, snackbar)
    ScreenScaffold(if (vm.isEditing) "Edit goal" else "New goal", onBack, snackbar) { padding ->
        FormColumn(padding) {
            AppTextField(f.name, { v -> vm.update { it.copy(name = v.take(60)) } }, "Goal name")
            MoneyField(f.targetText, { v -> vm.update { it.copy(targetText = v) } }, "Target amount")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Set a target date", Modifier.weight(1f)); Switch(f.hasDate, { v -> vm.update { it.copy(hasDate = v) } })
            }
            if (f.hasDate) DatePickerField(f.date, { d -> vm.update { it.copy(date = d) } })
            ChipSelect("Priority", GoalPriority.entries, f.priority, { p -> vm.update { it.copy(priority = p) } }) { it.label }
            if (vm.isEditing) ChipSelect("Status", GoalStatus.entries, f.status, { s -> vm.update { it.copy(status = s) } }) { it.label }
            AppTextField(f.description, { v -> vm.update { it.copy(description = v.take(200)) } }, "Notes (optional)", singleLine = false, minLines = 2)
            ErrorList(f.errors)
            Button(vm::submit, Modifier.fillMaxWidth()) { Text("Save goal") }
        }
    }
}

// ---- detail --------------------------------------------------------------------------------

@HiltViewModel
class GoalDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: GoalRepository,
    accountRepo: AccountRepository,
    private val addContribution: AddContributionUseCase,
    private val save: SaveGoalUseCase,
    private val prefs: UserPreferencesRepository,
    private val clock: Clock,
) : UiViewModel() {
    private val id = handle.get<Long>(Routes.ARG_ID) ?: -1L
    val goal: StateFlow<GoalWithSaved?> = repo.observeGoal(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val contributions: StateFlow<List<GoalContribution>> = repo.observeContributions(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts: StateFlow<List<AccountWithBalance>> = accountRepo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun contribute(withdraw: Boolean, amountText: String, accountId: Long?, note: String) {
        val g = goal.value ?: return
        launchSafely {
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            val minor = (MoneyParser.parse(amountText, currency = cur) as? MoneyParseResult.Valid)?.minor
            if (minor == null || minor <= 0) { say("Enter a valid amount."); return@launchSafely }
            handle(addContribution(g, if (withdraw) -minor else minor, accountId, clock.millis(), note)) { say(if (withdraw) "Withdrawn." else "Added to your goal.") }
        }
    }

    fun deleteContribution(cid: Long) { launchSafely { repo.deleteContribution(cid) } }
    fun setStatus(s: GoalStatus) { val g = goal.value ?: return; launchSafely { handle(save(g.goal.copy(status = s))) { } } }
    fun delete() { launchSafely { repo.delete(id); finish() } }
}

@Composable
fun GoalDetailScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, vm: GoalDetailViewModel = hiltViewModel()) {
    val g by vm.goal.collectAsStateWithLifecycle()
    val contributions by vm.contributions.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var dialogWithdraw by remember { mutableStateOf<Boolean?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    CollectDone(vm.done, onBack); CollectMessages(vm.messages, snackbar)
    ScreenScaffold(g?.goal?.name ?: "Goal", onBack, snackbar, actions = {
        g?.let { IconButton(onClick = { onEdit(it.goal.id) }) { Icon(Icons.Filled.Edit, "Edit") }; IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") } }
    }) { padding ->
        val goal = g
        if (goal == null) { LoadingBox(padding); return@ScreenScaffold }
        val bps = SavingsMath.progressBps(goal.savedMinor, goal.goal.targetMinor)
        val plan = goal.goal.targetDate?.let { SavingsMath.plan(goal.goal.targetMinor, goal.savedMinor, LocalDate.now(), it) }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                InfoCard {
                    Text("${bps / 100}% saved", style = MaterialTheme.typography.headlineMedium)
                    ProgressBar(bps / 10_000f, MaterialTheme.colorScheme.primary, description = "${bps / 100} percent saved")
                    MoneyStatRow("Saved", goal.savedMinor); MoneyStatRow("Target", goal.goal.targetMinor)
                    MoneyStatRow("Remaining", (goal.goal.targetMinor - goal.savedMinor).coerceAtLeast(0))
                    StatRow("Status", goal.goal.status.label); StatRow("Priority", goal.goal.priority.label)
                    goal.goal.targetDate?.let { StatRow("Target date", it.toString()) }
                    if (goal.goal.description.isNotBlank()) Text(goal.goal.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (goal.goal.targetDate != null) item {
                InfoCard {
                    SectionTitle("What it takes")
                    if (plan == null) Text("The target date has passed. Edit the goal to set a new date.") else {
                        if (plan.remainingMinor == 0L) Text("You have already reached this goal.") else {
                            MoneyStatRow("Per day", plan.dailyMinor); MoneyStatRow("Per week", plan.weeklyMinor); MoneyStatRow("Per month (${plan.months} months)", plan.monthlyMinor)
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { dialogWithdraw = false }) { Text("Add money") }
                    OutlinedButton(onClick = { dialogWithdraw = true }) { Text("Withdraw") }
                    if (goal.goal.status == GoalStatus.ACTIVE) TextButton(onClick = { vm.setStatus(GoalStatus.PAUSED) }) { Text("Pause") }
                    if (goal.goal.status == GoalStatus.PAUSED) TextButton(onClick = { vm.setStatus(GoalStatus.ACTIVE) }) { Text("Resume") }
                }
            }
            item { Note("Goal savings are earmarked amounts. They don't move money between accounts and are never counted as income or expense.") }
            item { SectionTitle("History") }
            if (contributions.isEmpty()) item { Note("No contributions yet.") }
            items(contributions, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(java.time.Instant.ofEpochMilli(c.occurredAt).atZone(ZoneId.systemDefault()).toLocalDate().toString())
                        if (c.note.isNotBlank()) Note(c.note)
                    }
                    Money(c.amountMinor, prefix = if (c.amountMinor > 0) "+" else "", color = if (c.amountMinor > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    IconButton(onClick = { vm.deleteContribution(c.id) }) { Icon(Icons.Filled.Delete, "Delete entry") }
                }
            }
        }
    }
    dialogWithdraw?.let { withdraw ->
        var amount by rememberSaveable { mutableStateOf("") }
        var note by rememberSaveable { mutableStateOf("") }
        var account by remember { mutableStateOf<Long?>(null) }
        AlertDialog(onDismissRequest = { dialogWithdraw = null }, title = { Text(if (withdraw) "Withdraw" else "Add money") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoneyField(amount, { amount = it }, "Amount")
                AppTextField(note, { note = it.take(100) }, "Note (optional)")
                if (accounts.isNotEmpty()) ChipSelect("Account it relates to (optional)", accounts, accounts.firstOrNull { it.account.id == account }, { a -> account = if (account == a.account.id) null else a.account.id }) { it.account.name }
            }
        }, confirmButton = { TextButton(onClick = { vm.contribute(withdraw, amount, account, note); dialogWithdraw = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { dialogWithdraw = null }) { Text("Cancel") } })
    }
    if (confirmDelete) ConfirmDialog("Delete this goal?", "Its contribution history is deleted too. Your accounts and transactions are not affected.", "Delete", vm::delete) { confirmDelete = false }
}
