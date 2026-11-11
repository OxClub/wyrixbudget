package com.wyrix.budget.presentation.analytics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.calc.ConsolidatedBalance
import com.wyrix.budget.domain.calc.ForecastEngine
import com.wyrix.budget.domain.calc.ForecastResult
import com.wyrix.budget.domain.usecase.PlanningSnapshot
import com.wyrix.budget.presentation.components.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import javax.inject.Inject

data class ForecastState(val loading: Boolean = true, val horizon: Int = 30, val result: ForecastResult? = null, val balance: ConsolidatedBalance? = null, val currency: CurrencyInfo = CurrencyInfo.INR)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ForecastViewModel @Inject constructor(private val snapshot: PlanningSnapshot, prefs: UserPreferencesRepository) : UiViewModel() {
    private val horizon = MutableStateFlow(30)
    val state: StateFlow<ForecastState> = combine(horizon, prefs.settings) { h, s -> h to s.baseCurrency }.mapLatest { (h, code) ->
        val balance = snapshot.balance()
        ForecastState(false, h, ForecastEngine.forecast(LocalDate.now(), h, balance.totalMinor, snapshot.scheduledItems()), balance, CurrencyInfo.byCode(code))
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ForecastState())

    fun setHorizon(days: Int) { horizon.value = days }
}

@Composable
fun ForecastScreen(onBack: () -> Unit, vm: ForecastViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    ScreenScaffold("Balance forecast", onBack) { padding ->
        val r = s.result
        if (s.loading || r == null) { LoadingBox(padding); return@ScreenScaffold }
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { ChipSelect(null, listOf(7, 30, 90, 180), s.horizon, vm::setHorizon) { "$it days" } }
            item {
                InfoCard {
                    Text("Projection, not an actual balance", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
                    MoneyStatRow("Balance today", r.startBalanceMinor, s.currency)
                    MoneyStatRow("Expected inflows", r.inflowMinor, s.currency, MaterialTheme.colorScheme.primary)
                    MoneyStatRow("Expected outflows", r.outflowMinor, s.currency)
                    MoneyStatRow("Projected balance in ${s.horizon} days", r.endBalanceMinor, s.currency, if (r.endBalanceMinor >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if (r.lowestDate != null) MoneyStatRow("Lowest point (${r.lowestDate})", r.lowestBalanceMinor, s.currency, if (r.lowestBalanceMinor < 0) MaterialTheme.colorScheme.error else androidx.compose.ui.graphics.Color.Unspecified)
                    if (r.lowestBalanceMinor < 0) Text("Your balance may go below zero before income arrives. Review upcoming payments.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                    s.balance?.let { b ->
                        if (b.excludedAccounts.isNotEmpty()) Note("Not included (no exchange rate set): ${b.excludedAccounts.joinToString()}.")
                    }
                }
            }
            item { SectionTitle("What's coming") }
            if (r.events.isEmpty()) item { Note("Nothing is scheduled in this period. Add bills, subscriptions, loans or recurring items to see them here.") }
            var running = r.startBalanceMinor
            val rows = r.events.map { e -> running += e.amountMinor; e to running }
            itemsIndexed(rows) { _, (e, bal) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) { Text(e.label, style = MaterialTheme.typography.bodyMedium); Note("${e.date} · ${e.source}") }
                    Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                        Money(e.amountMinor, currency = s.currency, prefix = if (e.amountMinor > 0) "+" else "", color = if (e.amountMinor > 0) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Unspecified)
                        Money(bal, currency = s.currency, style = MaterialTheme.typography.bodySmall, color = if (bal < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { InfoCard { SectionTitle("Assumptions"); r.assumptions.forEach { Note("• $it") } } }
        }
    }
}
