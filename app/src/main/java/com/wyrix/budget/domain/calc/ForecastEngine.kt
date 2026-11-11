package com.wyrix.budget.domain.calc

import java.time.LocalDate

/** A recorded, scheduled obligation or expected income. [amountMinor] is signed: income +, outflow -. */
data class ScheduledItem(
    val label: String,
    val amountMinor: Long,
    val anchor: LocalDate,
    val nextDate: LocalDate,
    val frequency: Frequency,
    val customDays: Int = 1,
    val endDate: LocalDate? = null,
    val source: String,
)

data class ForecastEvent(val date: LocalDate, val label: String, val amountMinor: Long, val source: String)

data class ForecastResult(
    val startBalanceMinor: Long,
    val horizonDays: Int,
    val events: List<ForecastEvent>,
    val endBalanceMinor: Long,
    val lowestBalanceMinor: Long,
    val lowestDate: LocalDate?,
    val inflowMinor: Long,
    val outflowMinor: Long,
    val assumptions: List<String>,
)

/**
 * Deterministic projection: current balance + every scheduled item that falls inside the horizon.
 * No statistics, no machine learning. Nothing that is not recorded is guessed.
 */
object ForecastEngine {
    fun forecast(today: LocalDate, horizonDays: Int, startBalanceMinor: Long, items: List<ScheduledItem>): ForecastResult {
        val end = today.plusDays(horizonDays.toLong())
        val events = ArrayList<ForecastEvent>()
        for (item in items) {
            var cursor: LocalDate? = item.nextDate
            if (cursor != null && cursor.isBefore(today)) {
                // Overdue / unconfirmed: assume it lands today, then resume the normal schedule.
                events += ForecastEvent(today, item.label + " (overdue)", item.amountMinor, item.source)
                cursor = Recurrence.nextAfter(item.anchor, item.frequency, item.customDays, today)
            }
            var guard = 0
            while (cursor != null && !cursor.isAfter(end) && guard++ < 2_000) {
                if (item.endDate != null && cursor.isAfter(item.endDate)) break
                if (!cursor.isBefore(today)) events += ForecastEvent(cursor, item.label, item.amountMinor, item.source)
                cursor = Recurrence.nextAfter(item.anchor, item.frequency, item.customDays, cursor)
            }
        }
        events.sortWith(compareBy<ForecastEvent> { it.date }.thenBy { it.label })

        var balance = startBalanceMinor
        var lowest = startBalanceMinor
        var lowestDate: LocalDate? = null
        var inflow = 0L
        var outflow = 0L
        for (e in events) {
            balance += e.amountMinor
            if (e.amountMinor >= 0) inflow += e.amountMinor else outflow += -e.amountMinor
            if (balance < lowest) { lowest = balance; lowestDate = e.date }
        }
        val assumptions = listOf(
            "Starts from your current recorded balances; this is a projection, not an actual balance.",
            "Includes only bills, subscriptions, loan instalments and recurring items you have recorded.",
            "Overdue items are assumed to be paid today. Items marked inactive, paused or cancelled are excluded.",
            "Everyday spending that is not scheduled is not included.",
        )
        return ForecastResult(startBalanceMinor, horizonDays, events, balance, lowest, lowestDate, inflow, outflow, assumptions)
    }
}
