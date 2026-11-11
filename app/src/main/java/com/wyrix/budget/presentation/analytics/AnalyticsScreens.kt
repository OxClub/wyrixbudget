package com.wyrix.budget.presentation.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.core.time.TimeRange
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.calc.*
import com.wyrix.budget.domain.model.BillStatus
import com.wyrix.budget.domain.model.SubscriptionStatus
import com.wyrix.budget.domain.repository.*
import com.wyrix.budget.presentation.components.*
import com.wyrix.budget.presentation.theme.chartColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

enum class AnalyticsPeriod(val label: String) {
    THIS_MONTH("This month"), LAST_MONTH("Last month"), LAST_30("Last 30 days"), THIS_FY("This financial year"), THIS_YEAR("This calendar year")
}

data class AnalyticsState(
    val loading: Boolean = true,
    val period: AnalyticsPeriod = AnalyticsPeriod.THIS_MONTH,
    val currency: CurrencyInfo = CurrencyInfo.INR,
    val incomeMinor: Long = 0, val expenseMinor: Long = 0, val prevIncomeMinor: Long = 0, val prevExpenseMinor: Long = 0,
    val expenseByCategory: List<CategoryAmountUi> = emptyList(), val incomeByCategory: List<CategoryAmountUi> = emptyList(),
    val monthLabels: List<String> = emptyList(), val monthIncome: List<Long> = emptyList(), val monthExpense: List<Long> = emptyList(),
    val merchants: List<LabeledAmount> = emptyList(), val accounts: List<LabeledAmount> = emptyList(),
    val avgDailyMinor: Long = 0, val topDay: LabeledAmount? = null,
    val insights: List<Insight> = emptyList(),
    val activeBudgets: Int = 0, val overBudgets: Int = 0, val goalsActive: Int = 0, val goalsSavedMinor: Long = 0,
) {
    val netMinor get() = incomeMinor - expenseMinor
}

data class CategoryAmountUi(val name: String, val amountMinor: Long)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val analytics: AnalyticsRepository, private val budgets: BudgetRepository, private val goals: GoalRepository,
    private val bills: BillRepository, private val subs: SubscriptionRepository, private val prefs: UserPreferencesRepository, private val clock: Clock,
) : UiViewModel() {
    private val period = MutableStateFlow(AnalyticsPeriod.THIS_MONTH)

    val state: StateFlow<AnalyticsState> = combine(period, analytics.observeChangeToken(), prefs.settings) { p, _, s -> p to s }
        .mapLatest { (p, s) -> build(p, s.baseCurrency, s.fyStartMonth, s.dismissedInsights) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AnalyticsState())

    fun setPeriod(p: AnalyticsPeriod) { period.value = p }
    fun dismiss(key: String) { viewModelScope.launch { prefs.dismissInsight(key) } }

    private suspend fun build(p: AnalyticsPeriod, baseCode: String, fyMonth: Int, dismissed: Set<String>): AnalyticsState {
        val zone = ZoneId.systemDefault(); val now = clock.instant(); val today = LocalDate.now()
        val cur = CurrencyInfo.byCode(baseCode)
        val range: TimeRange = when (p) {
            AnalyticsPeriod.THIS_MONTH -> PeriodCalculator.thisMonth(now, zone)
            AnalyticsPeriod.LAST_MONTH -> PeriodCalculator.lastMonth(now, zone)
            AnalyticsPeriod.LAST_30 -> PeriodCalculator.last30Days(now, zone)
            AnalyticsPeriod.THIS_FY -> PeriodCalculator.financialYear(now, zone, fyMonth)
            AnalyticsPeriod.THIS_YEAR -> PeriodCalculator.thisYear(now, zone)
        }
        val prev = PeriodCalculator.previous(range)
        val expCats = analytics.byCategory(true, range); val incCats = analytics.byCategory(false, range)
        val prevExp = analytics.byCategory(true, prev).sumOf { it.amountMinor }; val prevInc = analytics.byCategory(false, prev).sumOf { it.amountMinor }
        val expense = expCats.sumOf { it.amountMinor }; val income = incCats.sumOf { it.amountMinor }

        val trendRange = TimeRange(PeriodCalculator.thisMonth(now, zone).let { java.time.Instant.ofEpochMilli(it.fromMillis).atZone(zone).minusMonths(5).toInstant().toEpochMilli() }, PeriodCalculator.thisMonth(now, zone).toMillis)
        val months = analytics.monthlyTotals(trendRange, zone)

        val daily = analytics.dailySeries(true, range, zone)
        val elapsedDays = ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(range.fromMillis).atZone(zone).toLocalDate(), minOf(today.plusDays(1), java.time.Instant.ofEpochMilli(range.toMillis).atZone(zone).toLocalDate())).coerceAtLeast(1)

        // Insights always describe the current month.
        val thisMonth = PeriodCalculator.thisMonth(now, zone); val lastMonth = PeriodCalculator.lastMonth(now, zone)
        val monthExp = analytics.byCategory(true, thisMonth); val monthInc = analytics.byCategory(false, thisMonth)
        val activeBudgets = budgets.observeStatuses(today, zone).first().filter { !today.isBefore(it.budget.start) && !today.isAfter(it.budget.end) }
        val goalList = goals.observeGoals().first()
        val dueWeek = bills.observeBills().first().count { it.isActive && !it.nextDueDate.isAfter(today.plusDays(7)) } +
            subs.observeAll().first().count { it.status == SubscriptionStatus.ACTIVE && !it.renewalDate.isBefore(today) && !it.renewalDate.isAfter(today.plusDays(7)) }
        val daysIn = today.dayOfMonth.toLong()
        val insights = InsightEngine.generate(InsightInput(
            "${today.year}-${today.monthValue.toString().padStart(2, '0')}", cur, activeBudgets, monthInc.sumOf { it.amountMinor }, monthExp.sumOf { it.amountMinor },
            monthExp.associate { it.name to it.amountMinor }, analytics.byCategory(true, lastMonth).associate { it.name to it.amountMinor },
            monthExp.sumOf { it.amountMinor } / daysIn, dueWeek, goalList.filter { it.goal.status.name != "PAUSED" }.map { GoalProgress(it.goal.id, it.goal.name, SavingsMath.progressBps(it.savedMinor, it.goal.targetMinor)) },
        )).filter { it.key !in dismissed }

        return AnalyticsState(
            loading = false, period = p, currency = cur, incomeMinor = income, expenseMinor = expense, prevIncomeMinor = prevInc, prevExpenseMinor = prevExp,
            expenseByCategory = expCats.map { CategoryAmountUi(it.name, it.amountMinor) }, incomeByCategory = incCats.map { CategoryAmountUi(it.name, it.amountMinor) },
            monthLabels = months.map { it.month.takeLast(2) + "/" + it.month.substring(2, 4) }, monthIncome = months.map { it.incomeMinor }, monthExpense = months.map { it.expenseMinor },
            merchants = analytics.byMerchant(range), accounts = analytics.byAccount(range),
            avgDailyMinor = expense / elapsedDays, topDay = daily.maxByOrNull { it.amountMinor },
            insights = insights, activeBudgets = activeBudgets.size, overBudgets = activeBudgets.count { it.isOverspent },
            goalsActive = goalList.count { it.goal.status.name == "ACTIVE" }, goalsSavedMinor = goalList.sumOf { it.savedMinor },
        )
    }
}

private fun change(cur: Long, prev: Long): String? = if (prev <= 0) null else {
    val pct = (cur - prev) * 100 / prev
    if (pct == 0L) "no change" else if (pct > 0) "up $pct%" else "down ${-pct}%"
}

@Composable
fun AnalyticsScreen(onForecast: () -> Unit, onReports: () -> Unit, vm: AnalyticsViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold("Analytics") { padding ->
        if (s.loading) { LoadingBox(padding); return@ScreenScaffold }
        val colors = chartColors()
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ChipSelect(null, AnalyticsPeriod.entries, s.period, vm::setPeriod) { it.label } }
            item {
                InfoCard {
                    MoneyStatRow("Income", s.incomeMinor, s.currency, MaterialTheme.colorScheme.primary)
                    MoneyStatRow("Expenses", s.expenseMinor, s.currency)
                    MoneyStatRow("Net savings", s.netMinor, s.currency, if (s.netMinor >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    StatRow("Savings rate", if (s.incomeMinor > 0) "${s.netMinor * 100 / s.incomeMinor}%" else "n/a")
                    change(s.expenseMinor, s.prevExpenseMinor)?.let { Note("Expenses $it compared with the previous period.") }
                    change(s.incomeMinor, s.prevIncomeMinor)?.let { Note("Income $it compared with the previous period.") }
                    Note("Transfers, goal savings and reconciliation adjustments are not counted.")
                }
            }
            if (s.insights.isNotEmpty()) {
                item { SectionTitle("Insights for this month") }
                items(s.insights, key = { it.key }) { i ->
                    val c = when (i.severity) { InsightSeverity.WARNING -> MaterialTheme.colorScheme.tertiary; InsightSeverity.POSITIVE -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.secondary }
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Row(Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            ColorDot(c); Text(i.text, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = { vm.dismiss(i.key) }) { Icon(Icons.Filled.Close, "Dismiss insight") }
                        }
                    }
                }
            }
            if (s.expenseByCategory.isNotEmpty()) item {
                val top = s.expenseByCategory.take(6); val rest = s.expenseByCategory.drop(6).sumOf { it.amountMinor }
                val slices = top.mapIndexed { i, c -> ChartSlice(c.name, c.amountMinor, colors[i % colors.size]) } + if (rest > 0) listOf(ChartSlice("Other", rest, MaterialTheme.colorScheme.outline)) else emptyList()
                InfoCard {
                    SectionTitle("Where your money went")
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        DonutChart(slices, description = "Spending by category: " + slices.joinToString { "${it.label} ${it.value * 100 / s.expenseMinor.coerceAtLeast(1)} percent" })
                        Box(Modifier.weight(1f)) { Legend(slices, s.currency) }
                    }
                }
            } else item { InfoCard { Text("No expenses in this period yet.") } }
            if (s.monthLabels.isNotEmpty()) item {
                InfoCard {
                    SectionTitle("Last 6 months")
                    GroupedBarChart(s.monthLabels, s.monthIncome, s.monthExpense, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error, "Income and expenses by month", Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { Row(verticalAlignment = Alignment.CenterVertically) { ColorDot(MaterialTheme.colorScheme.primary); Text("  Income", style = MaterialTheme.typography.bodySmall) }; Row(verticalAlignment = Alignment.CenterVertically) { ColorDot(MaterialTheme.colorScheme.error); Text("  Expenses", style = MaterialTheme.typography.bodySmall) } }
                }
            }
            if (s.incomeByCategory.isNotEmpty()) item {
                InfoCard { SectionTitle("Income sources"); s.incomeByCategory.forEach { MoneyStatRow(it.name, it.amountMinor, s.currency) } }
            }
            item {
                InfoCard {
                    SectionTitle("Spending habits")
                    MoneyStatRow("Average per day", s.avgDailyMinor, s.currency)
                    s.topDay?.let { StatRow("Highest-spending day", "${it.label} · ${MoneyFormatter.format(it.amountMinor, s.currency)}") }
                }
            }
            if (s.merchants.isNotEmpty()) item { InfoCard { SectionTitle("Top merchants"); s.merchants.take(6).forEach { MoneyStatRow(it.label, it.amountMinor, s.currency) } } }
            if (s.accounts.size > 1) item { InfoCard { SectionTitle("Spending by account"); s.accounts.forEach { MoneyStatRow(it.label, it.amountMinor, s.currency) } } }
            item {
                InfoCard {
                    SectionTitle("Budgets and goals")
                    StatRow("Active budgets", "${s.activeBudgets} (${s.overBudgets} over)")
                    StatRow("Active savings goals", s.goalsActive.toString())
                    MoneyStatRow("Saved toward goals", s.goalsSavedMinor, s.currency)
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onForecast) { Text("Balance forecast") }
                    OutlinedButton(onClick = onReports) { Text("Reports") }
                }
            }
        }
    }
}
