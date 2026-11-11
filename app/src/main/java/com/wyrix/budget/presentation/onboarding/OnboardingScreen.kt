package com.wyrix.budget.presentation.onboarding

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyParseResult
import com.wyrix.budget.core.money.MoneyParser
import com.wyrix.budget.data.prefs.DemoMode
import com.wyrix.budget.data.prefs.PrefKeys
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.data.security.PinManager
import com.wyrix.budget.domain.calc.BudgetCalculator
import com.wyrix.budget.domain.calc.BudgetPeriodType
import com.wyrix.budget.domain.model.AccountType
import com.wyrix.budget.domain.model.ActionResult
import com.wyrix.budget.domain.model.Budget
import com.wyrix.budget.domain.model.ColorPalette
import com.wyrix.budget.domain.model.ThemeMode
import com.wyrix.budget.domain.usecase.CreateAccountUseCase
import com.wyrix.budget.domain.usecase.SaveBudgetUseCase
import com.wyrix.budget.presentation.components.*
import com.wyrix.budget.presentation.lock.AppRestarter
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import java.time.LocalDate
import javax.inject.Inject

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: UserPreferencesRepository,
    private val createAccount: CreateAccountUseCase,
    private val saveBudget: SaveBudgetUseCase,
    private val pin: PinManager,
) : UiViewModel() {
    private val _next = Channel<Unit>(Channel.BUFFERED)
    val next = _next.receiveAsFlow()
    private val _restart = Channel<Unit>(Channel.BUFFERED)
    val restart = _restart.receiveAsFlow()

    fun setBasics(currency: CurrencyInfo, theme: ThemeMode, name: String) {
        launchSafely { prefs.set(PrefKeys.BASE_CURRENCY, currency.code); prefs.setThemeMode(theme); prefs.set(PrefKeys.PROFILE_NAME, name.trim()); _next.send(Unit) }
    }

    fun addAccount(name: String, type: AccountType, balanceText: String) {
        launchSafely {
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            val opening = when (val p = MoneyParser.parse(balanceText, allowNegative = true, currency = cur)) {
                is MoneyParseResult.Valid -> p.minor; MoneyParseResult.Empty -> 0L
                MoneyParseResult.Invalid -> { say("Enter a valid balance."); return@launchSafely }
            }
            handle(createAccount(name, type, opening, "", ColorPalette.colors[0], cur.code)) { _next.send(Unit) }
        }
    }

    fun addBudget(totalText: String) {
        launchSafely {
            val cur = CurrencyInfo.byCode(prefs.settings.first().baseCurrency)
            val total = (MoneyParser.parse(totalText, currency = cur) as? MoneyParseResult.Valid)?.minor
            if (total == null || total <= 0) { say("Enter a monthly budget amount."); return@launchSafely }
            val (start, end) = BudgetCalculator.periodFor(BudgetPeriodType.MONTHLY, LocalDate.now())
            handle(saveBudget(Budget(0, "Monthly budget", BudgetPeriodType.MONTHLY, start, end, total, BudgetCalculator.DEFAULT_THRESHOLDS, false), emptyList())) { _next.send(Unit) }
        }
    }

    fun reminders(on: Boolean) { launchSafely { listOf(PrefKeys.NOTIFY_BILLS, PrefKeys.NOTIFY_BUDGETS, PrefKeys.NOTIFY_SUBSCRIPTIONS, PrefKeys.NOTIFY_GOALS, PrefKeys.NOTIFY_RECURRING).forEach { prefs.set(it, on) }; _next.send(Unit) } }

    fun setPin(p: String) { launchSafely { pin.setPin(p); _next.send(Unit) } }
    fun finishOnboarding() { launchSafely { prefs.set(PrefKeys.ONBOARDING_DONE, true) } }
    fun startDemo() { launchSafely { DemoMode.set(context, true); prefs.set(PrefKeys.ONBOARDING_DONE, true); _restart.send(Unit) } }
}

@Composable
fun OnboardingScreen(vm: OnboardingViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    var step by rememberSaveable { mutableIntStateOf(0) }
    CollectMessages(vm.messages, snackbar)
    LaunchedEffect(Unit) { vm.next.collect { step++ } }
    LaunchedEffect(Unit) { vm.restart.collect { AppRestarter.restart(context) } }

    var currency by rememberSaveable { mutableStateOf("INR") }
    var theme by rememberSaveable { mutableStateOf(ThemeMode.SYSTEM) }
    var name by rememberSaveable { mutableStateOf("") }
    var accName by rememberSaveable { mutableStateOf("Cash") }
    var accType by rememberSaveable { mutableStateOf(AccountType.CASH) }
    var balance by rememberSaveable { mutableStateOf("") }
    var budget by rememberSaveable { mutableStateOf("") }
    var pin1 by rememberSaveable { mutableStateOf("") }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.reminders(it) }

    Scaffold(containerColor = MaterialTheme.colorScheme.background, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.padding(padding).padding(24.dp).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            LinearProgressIndicator(progress = { (step + 1) / 6f }, modifier = Modifier.fillMaxWidth())
            when (step) {
                0 -> {
                    Text("Welcome to Budget", style = MaterialTheme.typography.headlineLarge)
                    Text("Track your money, plan ahead and keep everything private. Your data stays on this device, and Budget works without an internet connection.")
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { step++ }, Modifier.fillMaxWidth()) { Text("Set up in a minute") }
                    OutlinedButton(onClick = vm::startDemo, Modifier.fillMaxWidth()) { Text("Explore with demo data") }
                    TextButton(onClick = vm::finishOnboarding, Modifier.fillMaxWidth()) { Text("Skip setup") }
                }
                1 -> {
                    Text("The basics", style = MaterialTheme.typography.headlineMedium)
                    AppTextField(name, { name = it.take(40) }, "Your name (optional)")
                    ChipSelect("Main currency", CurrencyInfo.ALL, CurrencyInfo.byCode(currency), { currency = it.code }) { it.code }
                    ChipSelect("Appearance", ThemeMode.entries, theme, { theme = it }) { when (it) { ThemeMode.SYSTEM -> "System"; ThemeMode.DARK -> "Dark"; ThemeMode.LIGHT -> "Light" } }
                    Note("Language: English. More languages can be added later.")
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { vm.setBasics(CurrencyInfo.byCode(currency), theme, name) }, Modifier.fillMaxWidth()) { Text("Continue") }
                }
                2 -> {
                    Text("Add your first account", style = MaterialTheme.typography.headlineMedium)
                    AppTextField(accName, { accName = it.take(40) }, "Account name")
                    ChipSelect("Type", AccountType.entries, accType, { accType = it }) { it.label }
                    MoneyField(balance, { balance = it }, "Current balance", currency = CurrencyInfo.byCode(currency), allowNegative = true)
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { vm.addAccount(accName, accType, balance) }, Modifier.fillMaxWidth()) { Text("Continue") }
                    TextButton(onClick = { step++ }, Modifier.fillMaxWidth()) { Text("Skip") }
                }
                3 -> {
                    Text("Set a monthly budget", style = MaterialTheme.typography.headlineMedium)
                    Text("Optional. You can split it by category later.")
                    MoneyField(budget, { budget = it }, "Monthly spending limit", currency = CurrencyInfo.byCode(currency))
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { vm.addBudget(budget) }, Modifier.fillMaxWidth()) { Text("Continue") }
                    TextButton(onClick = { step++ }, Modifier.fillMaxWidth()) { Text("Skip") }
                }
                4 -> {
                    Text("Reminders", style = MaterialTheme.typography.headlineMedium)
                    Text("Get a nudge before bills and renewals, and when a budget is nearly used up. Everything is checked on your device.")
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.reminders(true) }, Modifier.fillMaxWidth()) { Text("Turn on reminders") }
                    TextButton(onClick = { vm.reminders(false) }, Modifier.fillMaxWidth()) { Text("Not now") }
                }
                else -> {
                    Text("Protect your data", style = MaterialTheme.typography.headlineMedium)
                    Text("Optional: lock Budget with a PIN (4 to 8 digits). Fingerprint unlock can be added in Settings.")
                    OutlinedTextField(pin1, { if (it.length <= 8 && it.all { c -> c.isDigit() }) pin1 = it }, label = { Text("PIN") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { vm.setPin(pin1); vm.finishOnboarding() }, enabled = pin1.length >= 4, modifier = Modifier.fillMaxWidth()) { Text("Set PIN and finish") }
                    TextButton(onClick = vm::finishOnboarding, Modifier.fillMaxWidth()) { Text("Finish without a PIN") }
                }
            }
        }
    }
}
