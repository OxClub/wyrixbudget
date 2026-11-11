package com.wyrix.budget.presentation.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.wyrix.budget.AppInfo
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.data.prefs.PrefKeys
import com.wyrix.budget.data.security.BiometricHelper
import com.wyrix.budget.domain.model.ActionResult
import com.wyrix.budget.domain.model.Category
import com.wyrix.budget.domain.model.CategoryKind
import com.wyrix.budget.domain.model.ExchangeRate
import com.wyrix.budget.domain.model.ThemeMode
import com.wyrix.budget.domain.repository.CategoryRepository
import com.wyrix.budget.domain.repository.DuplicateNameException
import com.wyrix.budget.domain.repository.ExchangeRateRepository
import com.wyrix.budget.domain.usecase.CreateCategoryUseCase
import com.wyrix.budget.core.money.CurrencyConversion
import com.wyrix.budget.presentation.components.*
import com.wyrix.budget.presentation.lock.AppRestarter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import javax.inject.Inject

private val months = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

@Composable
fun SettingsScreen(
    onBack: () -> Unit, onCategories: () -> Unit, onRates: () -> Unit, onAccounts: () -> Unit, onPrivacy: () -> Unit,
    vm: SettingsViewModel = hiltViewModel(),
) {
    val s by vm.settings.collectAsStateWithLifecycle()
    val pinSet by vm.pinSet.collectAsStateWithLifecycle()
    val restore by vm.restore.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages, snackbar)
    LaunchedEffect(Unit) { vm.restart.collect { AppRestarter.restart(context) } }

    var pinDialog by remember { mutableStateOf<String?>(null) }   // "set" | "remove"
    var backupPasswordDialog by remember { mutableStateOf(false) }
    var pendingBackupPassword by remember { mutableStateOf<CharArray?>(null) }
    var deleteDialog by remember { mutableStateOf(false) }
    var demoDialog by remember { mutableStateOf(false) }

    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri -> uri?.let { vm.createBackup(it, pendingBackupPassword) } }
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.openBackup(it) } }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) vm.set(PrefKeys.NOTIFY_BILLS, false)
    }

    fun requestNotifications() { if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }

    ScreenScaffold("Settings", onBack, snackbar) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (vm.demo) InfoCard { Text("Demo mode is on: you are looking at sample data.", color = MaterialTheme.colorScheme.tertiary) }

            InfoCard {
                SectionTitle("Profile")
                AppTextField(s.profileName, { vm.set(PrefKeys.PROFILE_NAME, it.take(40)) }, "Your name (optional)")
                ChipSelect("Currency", CurrencyInfo.ALL, CurrencyInfo.byCode(s.baseCurrency), { vm.set(PrefKeys.BASE_CURRENCY, it.code) }) { it.code }
                Note("Your base currency is used for budgets, totals and reports. Change it before you record transactions: existing ones keep the base amounts they were saved with.")
                OutlinedButton(onClick = onRates) { Text("Exchange rates") }
                ChipSelect("Financial year starts in", (1..12).toList(), s.fyStartMonth, { vm.set(PrefKeys.FY_START_MONTH, it) }) { months[it - 1].take(3) }
                Note("Language: English. Budget's text lives in one place so more languages can be added later.")
            }

            InfoCard {
                SectionTitle("Appearance")
                ChipSelect(null, ThemeMode.entries, s.themeMode, { vm.set(PrefKeys.THEME_MODE, it.name) }) { when (it) { ThemeMode.SYSTEM -> "System"; ThemeMode.DARK -> "Dark"; ThemeMode.LIGHT -> "Light" } }
                SwitchRow("Hide amounts", s.hideAmounts) { vm.set(PrefKeys.HIDE_AMOUNTS, it) }
            }

            InfoCard {
                SectionTitle("Notifications")
                OutlinedButton(onClick = { requestNotifications() }) { Text("Allow notifications") }
                SwitchRow("Bill and loan payments", s.notifyBills) { vm.set(PrefKeys.NOTIFY_BILLS, it) }
                SwitchRow("Subscription renewals", s.notifySubscriptions) { vm.set(PrefKeys.NOTIFY_SUBSCRIPTIONS, it) }
                SwitchRow("Budget alerts", s.notifyBudgets) { vm.set(PrefKeys.NOTIFY_BUDGETS, it) }
                SwitchRow("Savings goal milestones", s.notifyGoals) { vm.set(PrefKeys.NOTIFY_GOALS, it) }
                SwitchRow("Recurring items to confirm", s.notifyRecurring) { vm.set(PrefKeys.NOTIFY_RECURRING, it) }
                SwitchRow("Weekly and monthly summaries", s.notifySummary) { vm.set(PrefKeys.NOTIFY_SUMMARY, it) }
                Note("Reminders are checked once a day around 8 am, on your device only.")
            }

            InfoCard {
                SectionTitle("Security")
                if (!pinSet) Button(onClick = { pinDialog = "set" }) { Text("Set a PIN") } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onClick = { pinDialog = "set" }) { Text("Change PIN") }; OutlinedButton(onClick = { pinDialog = "remove" }) { Text("Remove PIN") } }
                    if (BiometricHelper.isAvailable(context)) SwitchRow("Unlock with fingerprint or face", s.biometricEnabled) { vm.set(PrefKeys.BIOMETRIC_ENABLED, it) }
                    ChipSelect("Lock after being away for", listOf(0, 30, 60, 300), s.autoLockSeconds, { vm.set(PrefKeys.AUTO_LOCK_SECONDS, it) }) { when (it) { 0 -> "Immediately"; 30 -> "30 s"; 60 -> "1 min"; else -> "5 min" } }
                    OutlinedButton(onClick = vm::lockNow) { Text("Lock now") }
                }
                SwitchRow("Block screenshots and hide in recent apps", s.blockScreenshots) { vm.set(PrefKeys.BLOCK_SCREENSHOTS, it) }
                Note("Your PIN is stored as a salted hash protected by the Android Keystore. If you forget it, the only way back in is to erase the app's data, so keep a backup.")
            }

            InfoCard {
                SectionTitle("Backup and restore")
                Text(if (s.lastBackupAt > 0) "Last backup: ${Instant.ofEpochMilli(s.lastBackupAt).atZone(ZoneId.systemDefault()).toLocalDate()}" else "You haven't made a backup yet.")
                Button(onClick = { backupPasswordDialog = true }) { Text("Create backup") }
                OutlinedButton(onClick = { vm.beginExternalFlow(); openDoc.launch(arrayOf("*/*")) }) { Text("Restore from backup") }
                ChipSelect("Remind me to back up", listOf(0, 7, 30), s.backupReminderDays, { vm.set(PrefKeys.BACKUP_REMINDER_DAYS, it) }) { when (it) { 0 -> "Never"; 7 -> "Weekly"; else -> "Monthly" } }
                Note("Backups contain all your data except receipt photos. They are saved wherever you choose; Budget never uploads anything.")
            }

            InfoCard {
                SectionTitle("Manage")
                OutlinedButton(onClick = onAccounts) { Text("Accounts") }
                OutlinedButton(onClick = onCategories) { Text("Categories") }
            }

            InfoCard {
                SectionTitle("Data")
                OutlinedButton(onClick = { demoDialog = true }) { Text(if (vm.demo) "Exit demo mode" else "Try demo mode") }
                OutlinedButton(onClick = { deleteDialog = true }) { Text("Delete all my data", color = MaterialTheme.colorScheme.error) }
            }

            InfoCard {
                SectionTitle("About")
                StatRow("Version", AppInfo.VERSION_NAME)
                OutlinedButton(onClick = onPrivacy) { Text("Privacy policy and terms") }
                OutlinedButton(onClick = { context.startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${AppInfo.SUPPORT_EMAIL}")).putExtra(Intent.EXTRA_SUBJECT, "Budget support")) }) { Text("Contact support") }
            }
        }
    }

    when (pinDialog) {
        "set" -> PinDialog(requireCurrent = pinSet, onDismiss = { pinDialog = null }) { cur, new -> vm.setPin(new, cur); pinDialog = null }
        "remove" -> PinDialog(requireCurrent = true, askNew = false, onDismiss = { pinDialog = null }) { cur, _ -> vm.removePin(cur ?: ""); pinDialog = null }
    }

    if (backupPasswordDialog) {
        var pw by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { backupPasswordDialog = false }, title = { Text("Protect this backup?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a password to encrypt it with AES-256. Leave empty for an unencrypted backup. A forgotten password can't be recovered.")
                OutlinedTextField(pw, { pw = it }, label = { Text("Password (optional)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }
        }, confirmButton = {
            TextButton(onClick = {
                pendingBackupPassword = pw.takeIf { it.isNotEmpty() }?.toCharArray(); backupPasswordDialog = false; vm.beginExternalFlow()
                createDoc.launch("wyrix-backup-${java.time.LocalDate.now()}.wyrixbackup")
            }) { Text("Continue") }
        }, dismissButton = { TextButton(onClick = { backupPasswordDialog = false }) { Text("Cancel") } })
    }

    when (val r = restore) {
        RestoreState.NeedsPassword -> {
            var pw by rememberSaveable { mutableStateOf("") }
            AlertDialog(onDismissRequest = vm::cancelRestore, title = { Text("Backup password") }, text = {
                OutlinedTextField(pw, { pw = it }, label = { Text("Password") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
            }, confirmButton = { TextButton(onClick = { vm.submitRestorePassword(pw.toCharArray()) }) { Text("Open") } }, dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } })
        }
        is RestoreState.Confirm -> AlertDialog(onDismissRequest = vm::cancelRestore, title = { Text("Restore this backup?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Made on ${Instant.ofEpochMilli(r.snapshot.createdAt).atZone(ZoneId.systemDefault()).toLocalDate()}. It contains:")
                r.snapshot.counts().forEach { (k, v) -> if (v > 0) Text("• $k: $v") }
                Text("Restoring REPLACES everything currently in Budget. If anything goes wrong your current data is kept.", color = MaterialTheme.colorScheme.error)
            }
        }, confirmButton = { TextButton(onClick = vm::confirmRestore) { Text("Replace my data") } }, dismissButton = { TextButton(onClick = vm::cancelRestore) { Text("Cancel") } })
        RestoreState.Working -> AlertDialog(onDismissRequest = {}, title = { Text("Restoring…") }, text = { LinearProgressIndicator() }, confirmButton = {})
        RestoreState.Idle -> Unit
    }

    if (deleteDialog) {
        var typed by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { deleteDialog = false }, title = { Text("Delete all data?") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("This permanently erases every account, transaction, budget, goal, receipt and setting on this device. It can't be undone. Make a backup first if you might need anything.")
                OutlinedTextField(typed, { typed = it }, label = { Text("Type DELETE to confirm") }, singleLine = true)
            }
        }, confirmButton = { TextButton(onClick = { deleteDialog = false; vm.deleteEverything() }, enabled = typed == "DELETE") { Text("Erase everything") } }, dismissButton = { TextButton(onClick = { deleteDialog = false }) { Text("Cancel") } })
    }
    if (demoDialog) ConfirmDialog(
        if (vm.demo) "Exit demo mode?" else "Try demo mode?",
        if (vm.demo) "The sample data is removed and your real data comes back. The app restarts." else "Budget restarts with clearly labelled sample data in a separate database. Your real data is untouched and hidden until you exit demo mode.",
        "Continue", { vm.setDemo(!vm.demo) }, { demoDialog = false },
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(checked, onChange) }
}

@Composable
private fun PinDialog(requireCurrent: Boolean, askNew: Boolean = true, onDismiss: () -> Unit, onDone: (String?, String) -> Unit) {
    var cur by rememberSaveable { mutableStateOf("") }; var a by rememberSaveable { mutableStateOf("") }; var b by rememberSaveable { mutableStateOf("") }
    fun field(v: String, set: (String) -> Unit, label: String) = @Composable {
        OutlinedTextField(v, { if (it.length <= 8 && it.all { c -> c.isDigit() }) set(it) }, label = { Text(label) }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword))
    }
    val valid = (!requireCurrent || cur.length >= 4) && (!askNew || (a.length >= 4 && a == b))
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (askNew) "Set PIN" else "Remove PIN") }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (requireCurrent) field(cur, { cur = it }, "Current PIN")()
            if (askNew) { field(a, { a = it }, "New PIN (4 to 8 digits)")(); field(b, { b = it }, "Repeat new PIN")() }
            if (askNew && b.isNotEmpty() && a != b) Text("The PINs don't match.", color = MaterialTheme.colorScheme.error)
        }
    }, confirmButton = { TextButton(onClick = { onDone(cur.takeIf { requireCurrent }, a) }, enabled = valid) { Text("Save") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

// ---- categories ----------------------------------------------------------------------------

@HiltViewModel
class CategoriesViewModel @Inject constructor(private val repo: CategoryRepository, private val create: CreateCategoryUseCase) : UiViewModel() {
    val categories: StateFlow<List<Category>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    fun add(name: String, kind: CategoryKind) { launchSafely { handle(create(name, kind)) { say("Category added.") } } }
    fun rename(c: Category, name: String) {
        launchSafely {
            val n = name.trim()
            if (n.isEmpty() || n.length > 30) { say("Enter a name up to 30 characters."); return@launchSafely }
            try { repo.rename(c.id, n); say("Renamed.") } catch (e: DuplicateNameException) { say("A category with this name already exists.") }
        }
    }
    fun delete(c: Category) { launchSafely { say(if (repo.deleteIfUnused(c.id)) "Deleted." else "\"${c.name}\" is used by transactions, budgets or recurring items, so it can't be deleted. Rename it instead.") } }
}

@Composable
fun CategoriesScreen(onBack: () -> Unit, vm: CategoriesViewModel = hiltViewModel()) {
    val list by vm.categories.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf<CategoryKind?>(null) }
    var editing by remember { mutableStateOf<Category?>(null) }
    var deleting by remember { mutableStateOf<Category?>(null) }
    CollectMessages(vm.messages, snackbar)
    ScreenScaffold("Categories", onBack, snackbar) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CategoryKind.entries.forEach { kind ->
                item { Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) { SectionTitle(if (kind == CategoryKind.INCOME) "Income" else "Expense", Modifier.weight(1f)); TextButton(onClick = { adding = kind }) { Icon(Icons.Filled.Add, null); Text("Add") } } }
                items(list.filter { it.kind == kind }, key = { it.id }) { c ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(Color(c.colorArgb)); Text(c.name, Modifier.weight(1f).padding(start = 12.dp))
                        IconButton(onClick = { editing = c }) { Icon(Icons.Filled.Edit, "Rename ${c.name}") }
                        IconButton(onClick = { deleting = c }) { Icon(Icons.Filled.Delete, "Delete ${c.name}") }
                    }
                }
            }
        }
    }
    adding?.let { kind ->
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(onDismissRequest = { adding = null }, title = { Text("New category") }, text = { OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.add(name, kind); adding = null }) { Text("Add") } }, dismissButton = { TextButton(onClick = { adding = null }) { Text("Cancel") } })
    }
    editing?.let { c ->
        var name by rememberSaveable(c.id) { mutableStateOf(c.name) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Rename category") }, text = { OutlinedTextField(name, { name = it.take(30) }, label = { Text("Name") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { vm.rename(c, name); editing = null }) { Text("Save") } }, dismissButton = { TextButton(onClick = { editing = null }) { Text("Cancel") } })
    }
    deleting?.let { c -> ConfirmDialog("Delete \"${c.name}\"?", "Only possible if nothing uses it. Otherwise rename it instead.", "Delete", { vm.delete(c) }) { deleting = null } }
}

// ---- exchange rates -------------------------------------------------------------------------

@HiltViewModel
class RatesViewModel @Inject constructor(private val repo: ExchangeRateRepository, prefs: com.wyrix.budget.data.prefs.UserPreferencesRepository, private val clock: Clock) : UiViewModel() {
    val rates: StateFlow<List<ExchangeRate>> = repo.observeAll().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val base: StateFlow<String> = prefs.settings.map { it.baseCurrency }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "INR")
    fun save(code: String, text: String) {
        launchSafely {
            val rate = CurrencyConversion.parseRate(text)
            if (rate == null) { say("Enter a rate greater than zero."); return@launchSafely }
            repo.upsert(ExchangeRate(code, rate.stripTrailingZeros().toPlainString(), clock.millis(), "manual")); say("Rate saved.")
        }
    }
    fun delete(code: String) { launchSafely { repo.delete(code) } }
    fun now(): Long = clock.millis()
}

@Composable
fun RatesScreen(onBack: () -> Unit, vm: RatesViewModel = hiltViewModel()) {
    val rates by vm.rates.collectAsStateWithLifecycle()
    val base by vm.base.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<String?>(null) }
    CollectMessages(vm.messages, snackbar)
    ScreenScaffold("Exchange rates", onBack, snackbar) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Note("Rates are entered by you. Budget never downloads or guesses them. Each rate means: 1 unit of the currency = this many $base. A rate older than 7 days is flagged so you can update it.") }
            items(CurrencyInfo.ALL.filter { it.code != base }, key = { it.code }) { c ->
                val r = rates.firstOrNull { it.currencyCode == c.code }
                val ageDays = r?.let { ChronoUnit.DAYS.between(Instant.ofEpochMilli(it.asOf), Instant.ofEpochMilli(vm.now())) }
                InfoCard(Modifier.clickable { editing = c.code }) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("${c.code} · ${c.name}"); Text(r?.let { "1 = ${it.rateToBase} $base" } ?: "not set") }
                    if (r != null) Note("Set ${Instant.ofEpochMilli(r.asOf).atZone(ZoneId.systemDefault()).toLocalDate()} (${r.source})" + if ((ageDays ?: 0) > 7) " · older than 7 days, consider updating" else "")
                }
            }
        }
    }
    editing?.let { code ->
        var text by rememberSaveable(code) { mutableStateOf(rates.firstOrNull { it.currencyCode == code }?.rateToBase ?: "") }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("1 $code in $base") }, text = {
            OutlinedTextField(text, { text = it.replace(',', '.') }, label = { Text("Rate") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
        }, confirmButton = { TextButton(onClick = { vm.save(code, text); editing = null }) { Text("Save") } },
            dismissButton = { Row { if (rates.any { it.currencyCode == code }) TextButton(onClick = { vm.delete(code); editing = null }) { Text("Remove") }; TextButton(onClick = { editing = null }) { Text("Cancel") } } })
    }
}

// ---- privacy and terms ----------------------------------------------------------------------

@Composable
fun PrivacyScreen(onBack: () -> Unit) {
    ScreenScaffold("Privacy policy and terms", onBack) { padding ->
        FormColumn(padding) {
            SectionTitle("Privacy policy")
            Text(AppInfo.PRIVACY_POLICY, style = MaterialTheme.typography.bodyMedium)
            SectionTitle("Terms of use")
            Text(AppInfo.TERMS, style = MaterialTheme.typography.bodyMedium)
            Note("This is a plain-language draft that describes how the app behaves. Have it reviewed by a qualified professional before publishing.")
        }
    }
}
