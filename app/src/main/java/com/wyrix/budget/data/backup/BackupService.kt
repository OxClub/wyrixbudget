package com.wyrix.budget.data.backup

import androidx.room.withTransaction
import com.wyrix.budget.data.files.SecureFileStore
import com.wyrix.budget.data.local.AppDatabase
import com.wyrix.budget.data.local.dao.BackupDao
import com.wyrix.budget.data.prefs.PrefKeys
import com.wyrix.budget.data.prefs.UserPreferencesRepository
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupService @Inject constructor(
    private val db: AppDatabase,
    private val dao: BackupDao,
    private val prefs: UserPreferencesRepository,
    private val files: SecureFileStore,
    private val clock: Clock,
) {
    /** Children before parents, so foreign keys are never violated while clearing. */
    private val clearOrder = listOf(
        "attachments", "recurring_entries", "recurring_rules", "loan_payments", "loans",
        "subscription_payments", "subscriptions", "bill_payments", "bills",
        "savings_contributions", "savings_goals", "budget_allocations", "budgets",
        "transactions", "exchange_rates", "categories", "accounts",
    )

    /** A consistent snapshot (one transaction) encoded and optionally password-encrypted. */
    suspend fun create(password: CharArray?): ByteArray {
        val snapshot = db.withTransaction {
            BackupSnapshot(
                BackupCodec.FORMAT_VERSION, BackupCodec.SCHEMA_VERSION, clock.millis(),
                dao.accounts(), dao.categories(), dao.transactions(), dao.budgets(), dao.allocations(),
                dao.goals(), dao.contributions(), dao.bills(), dao.billPayments(), dao.subscriptions(),
                dao.subscriptionPayments(), dao.loans(), dao.loanPayments(), dao.rules(), dao.entries(), dao.rates(),
            )
        }
        val bytes = BackupCodec.encode(snapshot, password)
        prefs.set(PrefKeys.LAST_BACKUP_AT, clock.millis())
        return bytes
    }

    /**
     * Replaces everything with the snapshot, all-or-nothing: if any insert fails the transaction rolls
     * back and existing data is untouched. Receipt images are not part of backups, so they are removed
     * together with their database rows.
     */
    suspend fun restore(s: BackupSnapshot) {
        BackupCodec.validate(s)
        db.withTransaction {
            clearTables()
            dao.putAccounts(s.accounts.orEmpty())
            dao.putCategories(s.categories.orEmpty())
            dao.putTransactions(s.transactions.orEmpty())
            dao.putBudgets(s.budgets.orEmpty())
            dao.putAllocations(s.allocations.orEmpty())
            dao.putGoals(s.goals.orEmpty())
            dao.putContributions(s.contributions.orEmpty())
            dao.putBills(s.bills.orEmpty())
            dao.putBillPayments(s.billPayments.orEmpty())
            dao.putSubscriptions(s.subscriptions.orEmpty())
            dao.putSubscriptionPayments(s.subscriptionPayments.orEmpty())
            dao.putLoans(s.loans.orEmpty())
            dao.putLoanPayments(s.loanPayments.orEmpty())
            dao.putRules(s.rules.orEmpty())
            dao.putEntries(s.entries.orEmpty())
            dao.putRates(s.rates.orEmpty())
        }
        files.wipe()
    }

    /** Permanently deletes all financial data, receipts and settings. */
    suspend fun wipeAll() {
        db.withTransaction { clearTables() }
        files.wipe()
        prefs.clearAll()
    }

    private fun clearTables() {
        val sql = db.openHelper.writableDatabase
        clearOrder.forEach { sql.execSQL("DELETE FROM `$it`") }
    }
}
