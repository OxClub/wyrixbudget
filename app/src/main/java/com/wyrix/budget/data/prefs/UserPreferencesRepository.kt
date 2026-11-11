package com.wyrix.budget.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.wyrix.budget.domain.model.ThemeMode
import com.wyrix.budget.domain.repository.BaseCurrencyProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "wyrix_settings")

object PrefKeys {
    val THEME_MODE = stringPreferencesKey("theme_mode")
    val HIDE_AMOUNTS = booleanPreferencesKey("hide_amounts")
    val PROFILE_NAME = stringPreferencesKey("profile_name")
    val BASE_CURRENCY = stringPreferencesKey("base_currency")
    val FY_START_MONTH = intPreferencesKey("fy_start_month")
    val ONBOARDING_DONE = booleanPreferencesKey("onboarding_done")
    val NOTIFY_BILLS = booleanPreferencesKey("notify_bills")
    val NOTIFY_BUDGETS = booleanPreferencesKey("notify_budgets")
    val NOTIFY_SUBSCRIPTIONS = booleanPreferencesKey("notify_subscriptions")
    val NOTIFY_GOALS = booleanPreferencesKey("notify_goals")
    val NOTIFY_RECURRING = booleanPreferencesKey("notify_recurring")
    val NOTIFY_SUMMARY = booleanPreferencesKey("notify_summary")
    val BACKUP_REMINDER_DAYS = intPreferencesKey("backup_reminder_days")
    val LAST_BACKUP_AT = longPreferencesKey("last_backup_at")
    val BIOMETRIC_ENABLED = booleanPreferencesKey("biometric_enabled")
    val AUTO_LOCK_SECONDS = intPreferencesKey("auto_lock_seconds")
    val BLOCK_SCREENSHOTS = booleanPreferencesKey("block_screenshots")
    val DISMISSED_INSIGHTS = stringSetPreferencesKey("dismissed_insights")
    val SENT_NOTIFICATIONS = stringSetPreferencesKey("sent_notifications")
    val PIN_BLOB = stringPreferencesKey("pin_blob")
}

data class AppSettings(
    val profileName: String = "",
    val baseCurrency: String = "INR",
    val fyStartMonth: Int = 4,                 // India: financial year starts in April
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val hideAmounts: Boolean = false,
    val onboardingDone: Boolean = false,
    val notifyBills: Boolean = true,
    val notifyBudgets: Boolean = true,
    val notifySubscriptions: Boolean = true,
    val notifyGoals: Boolean = true,
    val notifyRecurring: Boolean = true,
    val notifySummary: Boolean = false,
    val backupReminderDays: Int = 0,
    val lastBackupAt: Long = 0,
    val biometricEnabled: Boolean = false,
    val autoLockSeconds: Int = 30,
    val blockScreenshots: Boolean = false,
    val dismissedInsights: Set<String> = emptySet(),
)

@Singleton
class UserPreferencesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : BaseCurrencyProvider {
    private val store = context.dataStore

    private val safeData: Flow<Preferences> = store.data.catch { e ->
        if (e is IOException) emit(emptyPreferences()) else throw e
    }

    val settings: Flow<AppSettings> = safeData.map { p ->
        AppSettings(
            profileName = p[PrefKeys.PROFILE_NAME] ?: "",
            baseCurrency = p[PrefKeys.BASE_CURRENCY] ?: "INR",
            fyStartMonth = (p[PrefKeys.FY_START_MONTH] ?: 4).coerceIn(1, 12),
            themeMode = ThemeMode.entries.firstOrNull { it.name == p[PrefKeys.THEME_MODE] } ?: ThemeMode.SYSTEM,
            hideAmounts = p[PrefKeys.HIDE_AMOUNTS] ?: false,
            onboardingDone = p[PrefKeys.ONBOARDING_DONE] ?: false,
            notifyBills = p[PrefKeys.NOTIFY_BILLS] ?: true,
            notifyBudgets = p[PrefKeys.NOTIFY_BUDGETS] ?: true,
            notifySubscriptions = p[PrefKeys.NOTIFY_SUBSCRIPTIONS] ?: true,
            notifyGoals = p[PrefKeys.NOTIFY_GOALS] ?: true,
            notifyRecurring = p[PrefKeys.NOTIFY_RECURRING] ?: true,
            notifySummary = p[PrefKeys.NOTIFY_SUMMARY] ?: false,
            backupReminderDays = p[PrefKeys.BACKUP_REMINDER_DAYS] ?: 0,
            lastBackupAt = p[PrefKeys.LAST_BACKUP_AT] ?: 0,
            biometricEnabled = p[PrefKeys.BIOMETRIC_ENABLED] ?: false,
            autoLockSeconds = p[PrefKeys.AUTO_LOCK_SECONDS] ?: 30,
            blockScreenshots = p[PrefKeys.BLOCK_SCREENSHOTS] ?: false,
            dismissedInsights = p[PrefKeys.DISMISSED_INSIGHTS] ?: emptySet(),
        )
    }

    val themeMode: Flow<ThemeMode> = settings.map { it.themeMode }
    val hideAmounts: Flow<Boolean> = settings.map { it.hideAmounts }
    val pinBlob: Flow<String?> = safeData.map { it[PrefKeys.PIN_BLOB] }

    override suspend fun code(): String = safeData.first()[PrefKeys.BASE_CURRENCY] ?: "INR"

    suspend fun <T> set(key: Preferences.Key<T>, value: T) { store.edit { it[key] = value } }
    suspend fun remove(key: Preferences.Key<*>) { store.edit { it.remove(key) } }
    suspend fun setThemeMode(mode: ThemeMode) = set(PrefKeys.THEME_MODE, mode.name)
    suspend fun setHideAmounts(hide: Boolean) = set(PrefKeys.HIDE_AMOUNTS, hide)

    suspend fun dismissInsight(key: String) {
        store.edit { it[PrefKeys.DISMISSED_INSIGHTS] = (it[PrefKeys.DISMISSED_INSIGHTS] ?: emptySet()) + key }
    }

    /** Notification de-duplication: entries are "yyyy-MM-dd|key" and are pruned after 90 days. */
    suspend fun wasSent(key: String): Boolean =
        (safeData.first()[PrefKeys.SENT_NOTIFICATIONS] ?: emptySet()).any { it.substringAfter('|') == key }

    suspend fun markSent(key: String, today: LocalDate) {
        store.edit { p ->
            val cutoff = today.minusDays(90).toString()
            val kept = (p[PrefKeys.SENT_NOTIFICATIONS] ?: emptySet()).filter { it.substringBefore('|') >= cutoff }
            p[PrefKeys.SENT_NOTIFICATIONS] = kept.toSet() + "$today|$key"
        }
    }

    suspend fun clearAll() { store.edit { it.clear() } }
}

/** Demo mode swaps the whole database file, so sample data can never mix with real data. */
object DemoMode {
    private const val FILE = "wyrix_boot"
    private const val KEY = "demo_mode"
    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    fun isOn(c: Context): Boolean = prefs(c).getBoolean(KEY, false)
    fun set(c: Context, on: Boolean) { prefs(c).edit().putBoolean(KEY, on).commit() }
    fun dbName(c: Context): String = if (isOn(c)) "wyrix_demo.db" else "wyrix.db"
}
