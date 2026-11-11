package com.wyrix.budget.presentation.reports

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.data.export.CsvExporter
import com.wyrix.budget.data.export.ExportShare
import com.wyrix.budget.data.export.PdfExporter
import com.wyrix.budget.data.export.XlsxWriter
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.data.security.AppLockManager
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.usecase.ReportBuilder
import com.wyrix.budget.domain.usecase.ReportType
import com.wyrix.budget.presentation.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

enum class ReportPeriod(val label: String) { THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_30("Last 30 days"), THIS_FY("This financial year"), LAST_FY("Last financial year"), THIS_YEAR("This calendar year") }
enum class ReportFormat(val label: String, val ext: String, val mime: String) {
    PDF("PDF", "pdf", "application/pdf"),
    EXCEL("Excel", "xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    CSV("CSV", "csv", "text/csv"),
}

data class ReportFile(val file: File, val format: ReportFormat)

@HiltViewModel
class ReportsViewModel @Inject constructor(
    @ApplicationContext private val context: Context, private val builder: ReportBuilder, accountRepo: AccountRepository,
    private val prefs: UserPreferencesRepository, private val lock: AppLockManager, private val clock: Clock,
) : UiViewModel() {
    val accounts: StateFlow<List<AccountWithBalance>> = accountRepo.observeAccounts().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _file = Channel<ReportFile>(Channel.BUFFERED)
    val files = _file.receiveAsFlow()
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun generate(type: ReportType, period: ReportPeriod, format: ReportFormat, accountId: Long?) {
        if (_busy.value) return
        _busy.value = true
        launchSafely("Couldn't create the report. Nothing was changed.") {
            try {
                val s = prefs.settings.first()
                val zone = ZoneId.systemDefault(); val now = clock.instant()
                val range = when (period) {
                    ReportPeriod.THIS_MONTH -> PeriodCalculator.thisMonth(now, zone)
                    ReportPeriod.LAST_MONTH -> PeriodCalculator.lastMonth(now, zone)
                    ReportPeriod.LAST_30 -> PeriodCalculator.last30Days(now, zone)
                    ReportPeriod.THIS_FY -> PeriodCalculator.financialYear(now, zone, s.fyStartMonth)
                    ReportPeriod.LAST_FY -> PeriodCalculator.financialYear(now, zone, s.fyStartMonth, yearsBack = 1)
                    ReportPeriod.THIS_YEAR -> PeriodCalculator.thisYear(now, zone)
                }
                if (type == ReportType.ACCOUNT_STATEMENT && accountId == null) { say("Choose an account for the statement."); return@launchSafely }
                val report = builder.build(type, range, period.label, CurrencyInfo.byCode(s.baseCurrency), zone, LocalDate.now(), accountId)
                val file = withContext(Dispatchers.IO) {
                    val f = ExportShare.cacheFile(context, "wyrix-${type.name.lowercase()}-${LocalDate.now()}.${format.ext}")
                    when (format) {
                        ReportFormat.PDF -> f.outputStream().use { PdfExporter.write(report, it) }
                        ReportFormat.EXCEL -> f.outputStream().use { XlsxWriter.write(report, it) }
                        ReportFormat.CSV -> f.writeText(CsvExporter.toCsv(report), Charsets.UTF_8)
                    }
                    f
                }
                lock.beginExternalFlow()
                _file.send(ReportFile(file, format))
            } finally { _busy.value = false }
        }
    }
}

@Composable
fun ReportsScreen(onBack: () -> Unit, vm: ReportsViewModel = hiltViewModel()) {
    val accounts by vm.accounts.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var type by remember { mutableStateOf(ReportType.MONTHLY_SUMMARY) }
    var period by remember { mutableStateOf(ReportPeriod.THIS_MONTH) }
    var format by remember { mutableStateOf(ReportFormat.PDF) }
    var account by remember { mutableStateOf<Long?>(null) }
    var last by remember { mutableStateOf<ReportFile?>(null) }
    CollectMessages(vm.messages, snackbar)
    LaunchedEffect(Unit) { vm.files.collect { rf -> last = rf; context.startActivity(ExportShare.shareIntent(context, rf.file, rf.format.mime)) } }
    ScreenScaffold("Reports", onBack, snackbar) { padding ->
        FormColumn(padding) {
            ChipSelect("Report", ReportType.entries, type, { type = it }) { it.label }
            ChipSelect("Period", ReportPeriod.entries, period, { period = it }) { it.label }
            if (type == ReportType.ACCOUNT_STATEMENT) AccountChips("Account", accounts, account, { account = it }, optional = false)
            ChipSelect("Format", ReportFormat.entries, format, { format = it }) { it.label }
            Button(onClick = { vm.generate(type, period, format, account) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Creating…" else "Create and share") }
            last?.takeIf { it.format == ReportFormat.PDF }?.let { rf ->
                OutlinedButton(onClick = { ExportShare.print(context, rf.file, rf.file.name) }, modifier = Modifier.fillMaxWidth()) { Text("Print last PDF") }
            }
            Note("Reports are built on your device and shared only where you choose. Amounts are in your base currency; transfers and reconciliation adjustments are excluded from income and expenses.")
        }
    }
}
