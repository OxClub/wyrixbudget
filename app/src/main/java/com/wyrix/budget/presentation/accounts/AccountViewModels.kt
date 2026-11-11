package com.wyrix.budget.presentation.accounts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.money.MoneyParseResult
import com.wyrix.budget.core.money.MoneyParser
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.ActionResult
import com.wyrix.budget.domain.model.ColorPalette
import com.wyrix.budget.domain.model.TransactionFilter
import com.wyrix.budget.domain.model.TransactionListItem
import com.wyrix.budget.domain.repository.AccountRepository
import com.wyrix.budget.domain.repository.TransactionRepository
import com.wyrix.budget.domain.usecase.ArchiveAccountUseCase
import com.wyrix.budget.domain.usecase.CreateAccountUseCase
import com.wyrix.budget.domain.usecase.ReconcileAccountUseCase
import com.wyrix.budget.presentation.navigation.Routes
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

// ---- Accounts list ----------------------------------------------------------------------------

data class AccountsUiState(
    val isLoading: Boolean = true,
    val accounts: List<AccountWithBalance> = emptyList(),
    val totalBalanceMinor: Long = 0,
    val excludedAccounts: List<String> = emptyList(),
    val baseCurrency: com.wyrix.budget.core.money.CurrencyInfo = com.wyrix.budget.core.money.CurrencyInfo.INR,
    val hideAmounts: Boolean = false,
)

@HiltViewModel
class AccountsViewModel @Inject constructor(
    accounts: AccountRepository,
    rates: com.wyrix.budget.domain.repository.ExchangeRateRepository,
    prefs: UserPreferencesRepository,
) : ViewModel() {
    val uiState: StateFlow<AccountsUiState> = combine(accounts.observeAccounts(), prefs.settings, rates.observeAll()) { list, settings, rateList ->
        val base = com.wyrix.budget.core.money.CurrencyInfo.byCode(settings.baseCurrency)
        val c = com.wyrix.budget.domain.calc.Consolidation.balance(list, base, rateList.associateBy { it.currencyCode })
        AccountsUiState(
            isLoading = false,
            accounts = list,
            totalBalanceMinor = c.totalMinor,
            excludedAccounts = c.excludedAccounts,
            baseCurrency = base,
            hideAmounts = settings.hideAmounts,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountsUiState())
}

// ---- Add account -----------------------------------------------------------------------------

data class AddAccountForm(
    val name: String = "",
    val type: AccountType = AccountType.BANK,
    val openingBalanceText: String = "",
    val description: String = "",
    val colorArgb: Int = ColorPalette.colors.first(),
    val currencyCode: String = "INR",
    val errors: List<String> = emptyList(),
    val isSaving: Boolean = false,
)

@HiltViewModel
class AddAccountViewModel @Inject constructor(
    private val createAccount: CreateAccountUseCase,
    private val prefs: UserPreferencesRepository,
) : ViewModel() {
    private val _form = MutableStateFlow(AddAccountForm())
    val form: StateFlow<AddAccountForm> = _form

    private val _done = Channel<Unit>(Channel.BUFFERED)
    val done: Flow<Unit> = _done.receiveAsFlow()

    private var inFlight = false

    init { viewModelScope.launch { _form.update { it.copy(currencyCode = prefs.settings.first().baseCurrency) } } }

    fun onCurrencyChange(code: String) = _form.update { it.copy(currencyCode = code) }

    fun onNameChange(v: String) = _form.update { it.copy(name = v.take(CreateAccountUseCase.MAX_ACCOUNT_NAME), errors = emptyList()) }
    fun onTypeChange(v: AccountType) = _form.update { it.copy(type = v) }
    fun onDescriptionChange(v: String) = _form.update { it.copy(description = v.take(CreateAccountUseCase.MAX_DESCRIPTION)) }
    fun onColorChange(v: Int) = _form.update { it.copy(colorArgb = v) }

    fun onOpeningBalanceChange(raw: String) {
        val normalized = raw.replace(',', '.')
        if (BALANCE_INPUT.matches(normalized)) _form.update { it.copy(openingBalanceText = normalized, errors = emptyList()) }
    }

    fun save() {
        if (inFlight) return
        val f = _form.value
        val opening: Long = when (val parsed = MoneyParser.parse(f.openingBalanceText, allowNegative = true, currency = com.wyrix.budget.core.money.CurrencyInfo.byCode(f.currencyCode))) {
            is MoneyParseResult.Valid -> parsed.minor
            MoneyParseResult.Empty -> 0L // an empty opening balance means zero
            MoneyParseResult.Invalid -> {
                _form.update { it.copy(errors = listOf("Enter a valid opening balance.")) }
                return
            }
        }
        inFlight = true
        _form.update { it.copy(isSaving = true, errors = emptyList()) }
        viewModelScope.launch {
            try {
                when (val r = createAccount(f.name, f.type, opening, f.description, f.colorArgb, f.currencyCode)) {
                    is ActionResult.Success -> _done.send(Unit)
                    is ActionResult.Rejected -> fail(r.messages)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(listOf("Couldn't save the account. Nothing was changed, please try again."))
            }
        }
    }

    private fun fail(messages: List<String>) {
        inFlight = false
        _form.update { it.copy(isSaving = false, errors = messages) }
    }

    private companion object {
        val BALANCE_INPUT = Regex("""^-?\d{0,10}(\.\d{0,2})?$""")
    }
}

// ---- Account detail ---------------------------------------------------------------------------

data class AccountDetailUiState(
    val isLoading: Boolean = true,
    val account: AccountWithBalance? = null,
    val hideAmounts: Boolean = false,
)

sealed interface AccountDetailEvent {
    data object Closed : AccountDetailEvent
    data class Message(val text: String) : AccountDetailEvent
}

@HiltViewModel
class AccountDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    accounts: AccountRepository,
    transactions: TransactionRepository,
    prefs: UserPreferencesRepository,
    private val reconcileAccount: ReconcileAccountUseCase,
    private val archiveAccount: ArchiveAccountUseCase,
) : ViewModel() {

    private val accountId: Long = savedStateHandle.get<Long>(Routes.ARG_ACCOUNT_ID) ?: -1L

    val uiState: StateFlow<AccountDetailUiState> = combine(
        accounts.observeAccount(accountId),
        prefs.hideAmounts,
    ) { account, hide -> AccountDetailUiState(isLoading = false, account = account, hideAmounts = hide) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccountDetailUiState())

    val accountTransactions: Flow<PagingData<TransactionListItem>> =
        transactions.paged(TransactionFilter(accountId = accountId)).cachedIn(viewModelScope)

    private val _events = Channel<AccountDetailEvent>(Channel.BUFFERED)
    val events: Flow<AccountDetailEvent> = _events.receiveAsFlow()

    fun reconcile(actualBalanceText: String, reason: String) {
        val actual = MoneyParser.parse(actualBalanceText, allowNegative = true)
        if (actual !is MoneyParseResult.Valid) {
            viewModelScope.launch { _events.send(AccountDetailEvent.Message("Enter a valid balance.")) }
            return
        }
        viewModelScope.launch {
            try {
                when (val r = reconcileAccount(accountId, actual.minor, reason)) {
                    is ActionResult.Success -> {
                        val diff = r.value
                        _events.send(
                            AccountDetailEvent.Message(
                                if (diff == 0L) "Balance already matches. No adjustment needed."
                                else "Recorded an adjustment of ${MoneyFormatter.format(diff)}.",
                            ),
                        )
                    }
                    is ActionResult.Rejected -> _events.send(AccountDetailEvent.Message(r.messages.first()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(AccountDetailEvent.Message("Couldn't reconcile. Nothing was changed."))
            }
        }
    }

    fun archive() {
        viewModelScope.launch {
            try {
                when (val r = archiveAccount(accountId)) {
                    is ActionResult.Success -> _events.send(AccountDetailEvent.Closed)
                    is ActionResult.Rejected -> _events.send(AccountDetailEvent.Message(r.messages.first()))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.send(AccountDetailEvent.Message("Couldn't archive the account."))
            }
        }
    }
}
