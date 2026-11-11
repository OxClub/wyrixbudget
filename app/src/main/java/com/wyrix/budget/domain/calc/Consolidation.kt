package com.wyrix.budget.domain.calc

import com.wyrix.budget.core.money.CurrencyConversion
import com.wyrix.budget.core.money.CurrencyInfo
import com.wyrix.budget.domain.model.AccountWithBalance
import com.wyrix.budget.domain.model.ExchangeRate

data class ConsolidatedBalance(
    val totalMinor: Long,
    /** Accounts left out because no exchange rate has been entered for their currency. */
    val excludedAccounts: List<String>,
    /** Oldest rate date used (epoch millis), so the UI can flag stale rates. */
    val oldestRateAsOf: Long?,
)

object Consolidation {
    /** Sums balances in the base currency. Foreign accounts count only if the user has set a rate; never guessed. */
    fun balance(accounts: List<AccountWithBalance>, base: CurrencyInfo, rates: Map<String, ExchangeRate>): ConsolidatedBalance {
        var total = 0L
        val excluded = ArrayList<String>()
        var oldest: Long? = null
        for (a in accounts) {
            val code = a.account.currencyCode
            if (code == base.code) { total += a.balanceMinor; continue }
            val r = rates[code]
            val rate = r?.let { CurrencyConversion.parseRate(it.rateToBase) }
            if (r == null || rate == null) { excluded += a.account.name; continue }
            total += CurrencyConversion.convert(a.balanceMinor, CurrencyInfo.byCode(code), base, rate)
            oldest = if (oldest == null) r.asOf else minOf(oldest, r.asOf)
        }
        return ConsolidatedBalance(total, excluded, oldest)
    }
}
