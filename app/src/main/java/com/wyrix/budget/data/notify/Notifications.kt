package com.wyrix.budget.data.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.wyrix.budget.MainActivity
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.core.money.MoneyFormatter
import com.wyrix.budget.core.time.PeriodCalculator
import com.wyrix.budget.data.prefs.DemoMode
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import com.wyrix.budget.domain.calc.SavingsMath
import com.wyrix.budget.domain.model.GoalStatus
import com.wyrix.budget.domain.model.SubscriptionStatus
import com.wyrix.budget.domain.repository.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

object NotificationChannels {
    const val BILLS = "bills"
    const val BUDGETS = "budgets"
    const val SUBSCRIPTIONS = "subscriptions"
    const val GOALS = "goals"
    const val SUMMARIES = "summaries"
    const val BACKUP = "backup"

    fun create(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        listOf(
            BILLS to "Bill reminders", BUDGETS to "Budget alerts", SUBSCRIPTIONS to "Subscription renewals",
            GOALS to "Savings goals", SUMMARIES to "Summaries and recurring items", BACKUP to "Backup reminders",
        ).forEach { (id, name) -> nm.createNotificationChannel(NotificationChannel(id, name, NotificationManager.IMPORTANCE_DEFAULT)) }
    }
}

object Notifier {
    fun canPost(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    fun post(context: Context, channel: String, id: Int, title: String, text: String) {
        if (!canPost(context)) return
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setAutoCancel(true).build()
        @Suppress("MissingPermission") NotificationManagerCompat.from(context).notify(id, n)
    }
}

object ReminderScheduler {
    private const val NAME = "wyrix_daily_reminders"

    /** Runs once a day around 08:00. WorkManager restores the schedule after a reboot automatically. */
    fun schedule(context: Context) {
        val now = LocalDateTime.now()
        var first = now.with(LocalTime.of(8, 0))
        if (!first.isAfter(now)) first = first.plusDays(1)
        val delay = Duration.between(now, first).toMillis()
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(24, TimeUnit.HOURS).setInitialDelay(delay, TimeUnit.MILLISECONDS).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

/**
 * Daily check for reminders. Each notification has a stable key and is sent at most once; respects
 * every per-category toggle in settings. Does nothing while demo mode is on.
 */
@HiltWorker
class ReminderWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val prefs: UserPreferencesRepository,
    private val bills: BillRepository,
    private val subscriptions: SubscriptionRepository,
    private val loans: LoanRepository,
    private val budgets: BudgetRepository,
    private val goals: GoalRepository,
    private val recurring: RecurringRepository,
    private val analytics: AnalyticsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (DemoMode.isOn(context)) return Result.success()
        try {
            NotificationChannels.create(context)
            val s = prefs.settings.first()
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            val cur = CurrencyInfo.byCode(s.baseCurrency)
            var nextId = 1000

            suspend fun send(key: String, channel: String, title: String, text: String) {
                if (prefs.wasSent(key)) return
                Notifier.post(context, channel, nextId++, title, text)
                prefs.markSent(key, today)
            }

            if (s.notifyBills) {
                for (b in bills.observeBills().first().filter { it.isActive }) {
                    val days = ChronoUnit.DAYS.between(today, b.nextDueDate).toInt()
                    if (days < 0) send("bill-overdue:${b.id}:${b.nextDueDate}", NotificationChannels.BILLS, "Bill overdue", "${b.name} (${MoneyFormatter.format(b.amountMinor, cur)}) was due on ${b.nextDueDate}.")
                    else if (days in b.reminderDays) send("bill:${b.id}:${b.nextDueDate}:$days", NotificationChannels.BILLS, "Bill due " + whenText(days), "${b.name}: ${MoneyFormatter.format(b.amountMinor, cur)}")
                }
                for (l in loans.observeAll().first().filter { it.loan.isActive }) {
                    val days = ChronoUnit.DAYS.between(today, l.loan.nextPaymentDate).toInt()
                    if (days in 0..3) send("loan:${l.loan.id}:${l.loan.nextPaymentDate}:$days", NotificationChannels.BILLS, "Loan payment due " + whenText(days), "${l.loan.lender}: ${MoneyFormatter.format(l.loan.emiMinor, cur)}")
                }
            }
            if (s.notifySubscriptions) {
                for (sub in subscriptions.observeAll().first().filter { it.status == SubscriptionStatus.ACTIVE }) {
                    val days = ChronoUnit.DAYS.between(today, sub.renewalDate).toInt()
                    if (days in sub.reminderDays) send("sub:${sub.id}:${sub.renewalDate}:$days", NotificationChannels.SUBSCRIPTIONS, "${sub.name} renews " + whenText(days), MoneyFormatter.format(sub.amountMinor, cur))
                }
            }
            if (s.notifyBudgets) {
                for (st in budgets.observeStatuses(today, zone).first()) {
                    if (today.isBefore(st.budget.start) || today.isAfter(st.budget.end)) continue
                    val reached = st.reachedThreshold ?: continue
                    val text = if (reached >= 100) "You have reached your \"${st.budget.name}\" limit." else "You have used $reached% of your \"${st.budget.name}\" budget."
                    send("budget:${st.budget.id}:$reached:${st.budget.start}", NotificationChannels.BUDGETS, "Budget alert", text)
                    for (c in st.categories.filter { it.utilizationBps >= 10_000 }) {
                        send("budget-cat:${st.budget.id}:${c.categoryId}:${st.budget.start}", NotificationChannels.BUDGETS, "Category over budget", "You have exceeded your ${c.name} budget.")
                    }
                }
            }
            if (s.notifyGoals) {
                for (g in goals.observeGoals().first().filter { it.goal.status == GoalStatus.ACTIVE || it.goal.status == GoalStatus.COMPLETED }) {
                    val pct = SavingsMath.progressBps(g.savedMinor, g.goal.targetMinor) / 100
                    val milestone = listOf(100, 75, 50, 25).firstOrNull { pct >= it } ?: continue
                    send("goal:${g.goal.id}:$milestone", NotificationChannels.GOALS, if (milestone == 100) "Goal reached" else "Goal milestone", "\"${g.goal.name}\" is $milestone% funded.")
                }
            }
            if (s.notifyRecurring) {
                val due = recurring.observeRules().first().count { it.isActive && !it.nextDate.isAfter(today) }
                if (due > 0) send("recurring:$today", NotificationChannels.SUMMARIES, "Recurring items to confirm", "$due recurring item${if (due == 1) "" else "s"} waiting for your confirmation.")
            }
            if (s.notifySummary) {
                if (today.dayOfWeek == java.time.DayOfWeek.MONDAY) {
                    val range = com.wyrix.budget.core.time.TimeRange(
                        today.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(),
                        today.atStartOfDay(zone).toInstant().toEpochMilli(),
                    )
                    val spent = analytics.byCategory(true, range).sumOf { it.amountMinor }
                    send("summary-week:$today", NotificationChannels.SUMMARIES, "Your weekly summary", "You spent ${MoneyFormatter.format(spent, cur)} in the last 7 days.")
                }
                if (today.dayOfMonth == 1) {
                    val prev = PeriodCalculator.thisMonth(today.minusDays(1).atStartOfDay(zone).toInstant(), zone)
                    val spent = analytics.byCategory(true, prev).sumOf { it.amountMinor }
                    send("summary-month:$today", NotificationChannels.SUMMARIES, "Your monthly summary", "Last month you spent ${MoneyFormatter.format(spent, cur)}.")
                }
            }
            if (s.backupReminderDays > 0) {
                val last = s.lastBackupAt
                if (System.currentTimeMillis() - last >= s.backupReminderDays * 86_400_000L) send("backup:$today", NotificationChannels.BACKUP, "Time to back up", "Create a backup so your data is safe if you lose this phone.")
            }
            return Result.success()
        } catch (e: Exception) {
            return Result.retry()
        }
    }

    private fun whenText(days: Int) = when (days) { 0 -> "today"; 1 -> "tomorrow"; else -> "in $days days" }
}
