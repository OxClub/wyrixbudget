package com.wyrix.budget.presentation.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.calc.Consolidation
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.TransactionListItem
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.ExchangeRateRepository
import com.wyrix.budget.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = true,
    val accounts: List<AccountWithBalance> = emptyList(),
    val totalBalanceMinor: Long = 0,
    /** Accounts left out of the total because no exchange rate is set for their currency. */
    val excludedAccounts: List<String> = emptyList(),
    val monthIncomeMinor: Long = 0,
    val monthExpenseMinor: Long = 0,
    val recent: List<TransactionListItem> = emptyList(),
    val hideAmounts: Boolean = false,
    val baseCurrency: CurrencyInfo = CurrencyInfo.INR,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    accounts: AccountRepository,
    transactions: TransactionRepository,
    rates: ExchangeRateRepository,
    private val prefs: UserPreferencesRepository,
    clock: Clock,
) : ViewModel() {

    // Income and spending use base-currency amounts; transfers and adjustments are excluded by the query itself.
    private val monthRange = PeriodCalculator.thisMonth(clock.instant(), ZoneId.systemDefault())

    val uiState: StateFlow<HomeUiState> = combine(
        accounts.observeAccounts(),
        transactions.observeTotals(monthRange),
        transactions.observeRecent(RECENT_COUNT),
        prefs.settings,
        rates.observeAll(),
    ) { accountList, totals, recent, settings, rateList ->
        val base = CurrencyInfo.byCode(settings.baseCurrency)
        val consolidated = Consolidation.balance(accountList, base, rateList.associateBy { it.currencyCode })
        HomeUiState(
            isLoading = false,
            accounts = accountList,
            totalBalanceMinor = consolidated.totalMinor,
            excludedAccounts = consolidated.excludedAccounts,
            monthIncomeMinor = totals.incomeMinor,
            monthExpenseMinor = totals.expenseMinor,
            recent = recent,
            hideAmounts = settings.hideAmounts,
            baseCurrency = base,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun toggleHideAmounts() {
        viewModelScope.launch { prefs.setHideAmounts(!uiState.value.hideAmounts) }
    }

    private companion object {
        const val RECENT_COUNT = 6
    }
}
