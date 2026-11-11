package com.wyrix.budget.presentation.loans

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
import com.wyrix.budget.domain.calc.Frequency
import com.wyrix.budget.domain.calc.LoanMath
import com.wyrix.budget.domain.model.*
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.LoanRepository
import com.wyrix.budget.domain.usecase.RecordLoanPaymentUseCase
import com.wyrix.budget.domain.usecase.SaveLoanUseCase
import com.wyrix.budget.presentation.components.*
import com.wyrix.budget.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.math.BigDecimal
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class LoansViewModel @Inject constructor(repo: LoanRepository) : UiViewModel() {
    val loans: StateFlow<List<LoanWithOutstanding>?> = repo.observeAll().map<List<LoanWithOutstanding>, List<LoanWithOutstanding>?> { it }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun LoansScreen(onBack: () -> Unit, onOpen: (Long) -> Unit, onAdd: () -> Unit, onPayoffPlanner: () -> Unit, vm: LoansViewModel = hiltViewModel()) {
    val loans by vm.loans.collectAsStateWithLifecycle()
    ScreenScaffold("Debts and loans", onBack, floatingActionButton = {
        ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add debt") })
    }) { padding ->
        val list = loans
        when {
            list == null -> LoadingBox(padding)
            list.isEmpty() -> Box(Modifier.padding(padding).fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState("No debts recorded", "Add a loan, credit card balance or money you owe to see the payoff picture.", actionLabel = "Add debt", onAction = onAdd)
            }
            else -> LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val active = list.filter { it.loan.isActive }
                item {
                    InfoCard {
                        MoneyStatRow("Total outstanding", active.sumOf { it.outstandingMinor })
                        MoneyStatRow("Monthly payments", active.sumOf { it.loan.emiMinor })
                        OutlinedButton(onClick = onPayoffPlanner) { Text("Compare payoff strategies") }
                    }
                }
                items(list, key = { it.loan.id }) { l ->
                    InfoCard(Modifier.clickable { onOpen(l.loan.id) }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(l.loan.lender, style = MaterialTheme.typography.titleMedium)
                            Text(if (l.loan.isActive) l.loan.type.label else "Closed", style = MaterialTheme.typography.labelMedium)
                        }
                        MoneyStatRow("Outstanding", l.outstandingMinor)
                        if (l.loan.isActive) Note("Next payment ${l.loan.nextPaymentDate} · ${BigDecimal(l.loan.rateBps).movePointLeft(2).toPlainString()}% p.a.")
                        val bps = if (l.loan.outstandingStartMinor > 0) (l.paidMinor * 10_000 / l.loan.outstandingStartMinor) else 0L
                        ProgressBar(bps / 10_000f, MaterialTheme.colorScheme.primary, description = "${bps / 100} percent repaid")
                    }
                }
            }
        }
    }
}

data class LoanForm(
    val lender: String = "", val type: LoanType = LoanType.PERSONAL, val principalText: String = "", val outstandingText: String = "",
    val rateText: String = "", val emiText: String = "", val next: LocalDate = LocalDate.now().plusMonths(1),
    val hasEnd: Boolean = false, val end: LocalDate = LocalDate.now().plusYears(1), val accountId: Long? = null,
    val active: Boolean = true, val errors: List<String> = emptyList(),
)

@HiltViewModel
class LoanEditorViewModel @Inject constructor(
    handle: SavedStateHandle, private val repo: LoanRepository, private val save: SaveLoanUseCase,
    accountRepo: AccountRepository, private val prefs: UserPreferencesRepository,
) : UiViewModel() {
    private val id: Long? = (handle.get<Long>(Routes.ARG_ID) ?: -1L).takeIf { it > 0 }
    val isEditing = id != null
    private var original: Loan? = null
    private val _form = MutableStateFlow(LoanForm())
    val form: StateFlow<LoanForm> = _form
    val accounts = accountRepo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var saving = false

    init {
        id?.let { lid -> launchSafely {
            val row = repo.get(lid) ?: return@launchSafely
            val l = row.loan; original = l
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            _form.value = LoanForm(l.lender, l.type, MoneyFormatter.formatPlain(l.principalMinor, cur), MoneyFormatter.formatPlain(row.outstandingMinor, cur),
                BigDecimal(l.rateBps).movePointLeft(2).stripTrailingZeros().toPlainString(), MoneyFormatter.formatPlain(l.emiMinor, cur), l.nextPaymentDate, l.endDate != null, l.endDate ?: LocalDate.now().plusYears(1), l.accountId, l.isActive)
        } }
    }

    fun update(block: (LoanForm) -> LoanForm) = _form.update { block(it).copy(errors = emptyList()) }

    fun submit() {
        if (saving) return
        saving = true
        launchSafely { try {
            val f = _form.value
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            fun money(t: String) = (MoneyParser.parse(t, currency = cur) as? MoneyParseResult.Valid)?.minor
            val principal = money(f.principalText); val outstanding = money(f.outstandingText.ifBlank { f.principalText }); val emi = if (f.emiText.isBlank()) 0L else money(f.emiText)
            val rateBps = if (f.rateText.isBlank()) 0 else f.rateText.toBigDecimalOrNull()?.movePointRight(2)?.toInt()
            if (principal == null || outstanding == null || emi == null || rateBps == null) { _form.update { it.copy(errors = listOf("Check the amounts and interest rate.")) }; return@launchSafely }
            val o = original
            // Editing the outstanding amount re-bases the loan: payments already recorded keep reducing it from the new figure.
            val paidSoFar = if (o != null) repo.get(o.id)?.paidMinor ?: 0L else 0L
            val loan = Loan(id ?: 0, f.lender, f.type, principal, outstanding + paidSoFar, rateBps, emi, Frequency.MONTHLY,
                o?.startDate ?: f.next, if (f.hasEnd) f.end else null, f.next, f.accountId, f.active, o?.createdAt ?: 0)
            when (val r = save(loan)) { is ActionResult.Success -> finish(); is ActionResult.Rejected -> _form.update { it.copy(errors = r.messages) } }
        } finally { saving = false } }
    }

    fun delete() { val lid = id ?: return; launchSafely { repo.delete(lid); finish() } }
}

@Composable
fun LoanEditorScreen(onBack: () -> Unit, vm: LoanEditorViewModel = hiltViewModel()) {
    val f by vm.form.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var confirmDelete by remember { mutableStateOf(false) }
    CollectDone(vm.done, onBack); CollectMessages(vm.messages, snackbar)
    ScreenScaffold(if (vm.isEditing) "Edit debt" else "Add debt", onBack, snackbar, actions = {
        if (vm.isEditing) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
    }) { padding ->
        FormColumn(padding) {
            AppTextField(f.lender, { v -> vm.update { it.copy(lender = v.take(60)) } }, "Lender or person")
            ChipSelect("Type", LoanType.entries, f.type, { t -> vm.update { it.copy(type = t) } }) { it.label }
            MoneyField(f.principalText, { v -> vm.update { it.copy(principalText = v) } }, "Original amount")
            MoneyField(f.outstandingText, { v -> vm.update { it.copy(outstandingText = v) } }, "Outstanding now", supporting = "Leave empty if nothing has been repaid yet")
            NumberField(f.rateText, { v -> vm.update { it.copy(rateText = v) } }, "Interest rate (per year)", decimal = true, suffix = "%")
            MoneyField(f.emiText, { v -> vm.update { it.copy(emiText = v) } }, "Monthly payment (EMI)")
            SectionLabel("Next payment date"); DatePickerField(f.next, { d -> vm.update { it.copy(next = d) } })
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Has an end date", Modifier.weight(1f)); Switch(f.hasEnd, { v -> vm.update { it.copy(hasEnd = v) } }) }
            if (f.hasEnd) DatePickerField(f.end, { d -> vm.update { it.copy(end = d) } })
            AccountChips("Paid from (optional)", accounts, f.accountId, { a -> vm.update { it.copy(accountId = a) } })
            if (vm.isEditing) Row(verticalAlignment = Alignment.CenterVertically) { Text("Active", Modifier.weight(1f)); Switch(f.active, { v -> vm.update { it.copy(active = v) } }) }
            Note("Payments are assumed monthly. Interest for each payment is estimated as outstanding × annual rate ÷ 12.")
            ErrorList(f.errors)
            Button(vm::submit, Modifier.fillMaxWidth()) { Text("Save") }
        }
    }
    if (confirmDelete) ConfirmDialog("Delete this debt?", "Its payment history is deleted. Expenses you already recorded stay in your transactions.", "Delete", vm::delete) { confirmDelete = false }
}

@HiltViewModel
class LoanDetailViewModel @Inject constructor(
    handle: SavedStateHandle, repo: LoanRepository, accountRepo: AccountRepository, private val record: RecordLoanPaymentUseCase, private val prefs: UserPreferencesRepository,
) : UiViewModel() {
    private val id = handle.get<Long>(Routes.ARG_ID) ?: -1L
    val loan: StateFlow<LoanWithOutstanding?> = repo.observe(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val payments: StateFlow<List<LoanPayment>> = repo.observePayments(id).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val accounts = accountRepo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun pay(amountText: String, createExpense: Boolean, accountId: Long?) {
        launchSafely {
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            val minor = (MoneyParser.parse(amountText, currency = cur) as? MoneyParseResult.Valid)?.minor
            if (minor == null) { say("Enter a valid amount."); return@launchSafely }
            handle(record(id, minor, LocalDate.now(), createExpense, accountId)) { say("Payment recorded.") }
        }
    }
}

@Composable
fun LoanDetailScreen(onBack: () -> Unit, onEdit: (Long) -> Unit, vm: LoanDetailViewModel = hiltViewModel()) {
    val l by vm.loan.collectAsStateWithLifecycle()
    val payments by vm.payments.collectAsStateWithLifecycle()
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var paying by remember { mutableStateOf(false) }
    CollectMessages(vm.messages, snackbar)
    ScreenScaffold(l?.loan?.lender ?: "Debt", onBack, snackbar, actions = { l?.let { IconButton(onClick = { onEdit(it.loan.id) }) { Icon(Icons.Filled.Edit, "Edit") } } }) { padding ->
        val row = l
        if (row == null) { LoadingBox(padding); return@ScreenScaffold }
        val loan = row.loan
        val projection = if (loan.isActive && loan.emiMinor > 0) LoanMath.amortize(row.outstandingMinor, loan.rateBps, loan.emiMinor) else null
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                InfoCard {
                    MoneyStatRow("Outstanding", row.outstandingMinor); MoneyStatRow("Repaid so far", row.paidMinor); MoneyStatRow("Original amount", loan.principalMinor)
                    StatRow("Interest rate", "${BigDecimal(loan.rateBps).movePointLeft(2).toPlainString()}% per year")
                    if (loan.emiMinor > 0) MoneyStatRow("Monthly payment", loan.emiMinor)
                    if (loan.isActive) StatRow("Next payment", loan.nextPaymentDate.toString()) else Text("This debt is fully repaid.", color = MaterialTheme.colorScheme.primary)
                    if (loan.isActive) Button(onClick = { paying = true }) { Text("Record payment") }
                }
            }
            if (loan.isActive && loan.emiMinor > 0) item {
                InfoCard {
                    SectionTitle("Projection at the current payment")
                    if (projection == null) Text("The monthly payment does not cover the interest, so this debt will not be paid off. Increase the payment.", color = MaterialTheme.colorScheme.error)
                    else {
                        StatRow("Payments remaining", projection.schedule.size.toString())
                        StatRow("Estimated payoff", loan.nextPaymentDate.plusMonths((projection.schedule.size - 1).toLong()).toString())
                        MoneyStatRow("Interest still to pay", projection.totalInterestMinor)
                        MoneyStatRow("Total still to pay", projection.totalPaymentMinor)
                    }
                    Note("An estimate assuming monthly payments and a constant rate.")
                }
            }
            if (projection != null) {
                item { SectionTitle("Next payments") }
                items(projection.schedule.take(12), key = { it.index }) { r ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("#${r.index}"); Money(r.principalMinor, style = MaterialTheme.typography.bodySmall); Money(r.interestMinor, style = MaterialTheme.typography.bodySmall); Money(r.balanceMinor, style = MaterialTheme.typography.bodySmall)
                    }
                }
                item { Note("Columns: payment number, principal, interest, balance after.") }
            }
            item { SectionTitle("Payment history") }
            if (payments.isEmpty()) item { Note("No payments recorded yet.") }
            items(payments, key = { it.id }) { p ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column { Text(p.paidDate.toString()); Note("Principal ${MoneyFormatter.format(p.principalPartMinor, LocalBaseCurrency.current)} · interest ${MoneyFormatter.format(p.interestPartMinor, LocalBaseCurrency.current)}") }
                    Money(p.amountMinor)
                }
            }
        }
    }
    if (paying) {
        var amount by rememberSaveable { mutableStateOf("") }
        var record by remember { mutableStateOf(false) }
        var account by remember { mutableStateOf(l?.loan?.accountId ?: accounts.firstOrNull()?.account?.id) }
        AlertDialog(onDismissRequest = { paying = false }, title = { Text("Record payment") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MoneyField(amount, { amount = it }, "Amount paid", supporting = l?.loan?.emiMinor?.takeIf { it > 0 }?.let { "Usual payment ${MoneyFormatter.format(it, LocalBaseCurrency.current)}" })
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Also record as an expense", Modifier.weight(1f)); Switch(record, { record = it }) }
                if (record) AccountChips("Paid from", accounts, account, { account = it }, optional = false)
            }
        }, confirmButton = { TextButton(onClick = { vm.pay(amount, record, account); paying = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { paying = false }) { Text("Cancel") } })
    }
}
