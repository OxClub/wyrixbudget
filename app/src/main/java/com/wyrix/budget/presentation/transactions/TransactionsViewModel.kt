package com.wyrix.budget.presentation.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.core.time.TimeRange
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.Category
import com.wyrix.budget.domain.model.TransactionFilter
import com.wyrix.budget.domain.model.TransactionListItem
import com.wyrix.budget.domain.model.TransactionType
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.CategoryRepository
import com.wyrix.budget.domain.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Clock
import java.time.ZoneId
import javax.inject.Inject

enum class DatePreset(val label: String) {
    ALL("All time"),
    THIS_MONTH("This month"),
    LAST_30("Last 30 days"),
    THIS_YEAR("This year"),
}

data class TxFilterState(
    val query: String = "",
    val type: TransactionType? = null,
    val datePreset: DatePreset = DatePreset.ALL,
    val accountId: Long? = null,
    val categoryId: Long? = null,
    val ascending: Boolean = false,
) {
    val isActive: Boolean
        get() = query.isNotBlank() || type != null || datePreset != DatePreset.ALL ||
            accountId != null || categoryId != null
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: TransactionRepository,
    accountRepository: AccountRepository,
    categoryRepository: CategoryRepository,
    prefs: UserPreferencesRepository,
    private val clock: Clock,
) : ViewModel() {

    private val _filter = MutableStateFlow(TxFilterState())
    val filter: StateFlow<TxFilterState> = _filter

    val transactions: Flow<PagingData<TransactionListItem>> = _filter
        .debounce(FILTER_DEBOUNCE_MS)
        .distinctUntilChanged()
        .flatMapLatest { repository.paged(it.toDomainFilter()) }
        .cachedIn(viewModelScope)

    val accounts: StateFlow<List<AccountWithBalance>> = accountRepository.observeAccounts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val categories: StateFlow<List<Category>> = categoryRepository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val hideAmounts: StateFlow<Boolean> = prefs.hideAmounts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun onQueryChange(query: String) = _filter.update { it.copy(query = query) }
    fun onTypeChange(type: TransactionType?) = _filter.update { it.copy(type = type) }
    fun onDatePresetChange(preset: DatePreset) = _filter.update { it.copy(datePreset = preset) }
    fun onAccountChange(id: Long?) = _filter.update { it.copy(accountId = id) }
    fun onCategoryChange(id: Long?) = _filter.update { it.copy(categoryId = id) }
    fun toggleSort() = _filter.update { it.copy(ascending = !it.ascending) }
    fun clearFilters() = _filter.update { TxFilterState(ascending = it.ascending) }

    private fun TxFilterState.toDomainFilter(): TransactionFilter {
        val now = clock.instant()
        val zone = ZoneId.systemDefault()
        val range: TimeRange? = when (datePreset) {
            DatePreset.ALL -> null
            DatePreset.THIS_MONTH -> PeriodCalculator.thisMonth(now, zone)
            DatePreset.LAST_30 -> PeriodCalculator.last30Days(now, zone)
            DatePreset.THIS_YEAR -> PeriodCalculator.thisYear(now, zone)
        }
        return TransactionFilter(
            query = query,
            type = type,
            accountId = accountId,
            categoryId = categoryId,
            range = range,
            ascending = ascending,
        )
    }

    private companion object {
        const val FILTER_DEBOUNCE_MS = 200L
    }
}
