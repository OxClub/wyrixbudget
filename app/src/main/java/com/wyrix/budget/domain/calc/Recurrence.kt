package com.wyrix.budget.domain.calc

import java.time.LocalDate

enum class Frequency(val label: String, val months: Int, val days: Int) {
    ONCE("One time", 0, 0),
    DAILY("Daily", 0, 1),
    WEEKLY("Weekly", 0, 7),
    MONTHLY("Monthly", 1, 0),
    QUARTERLY("Quarterly", 3, 0),
    HALF_YEARLY("Half-yearly", 6, 0),
    YEARLY("Yearly", 12, 0),
    CUSTOM("Custom (every N days)", 0, 0),
}

/**
 * Date maths for repeating items. Occurrences are always computed from the original anchor date, so a
 * monthly item anchored on the 31st falls on Jan 31, Feb 28, Mar 31 (not drifting to the 28th).
 */
object Recurrence {
    private const val MAX_ITERATIONS = 20_000

    fun occurrence(anchor: LocalDate, f: Frequency, customDays: Int, k: Int): LocalDate? = when {
        f == Frequency.ONCE -> if (k == 0) anchor else null
        f.months > 0 -> anchor.plusMonths(f.months.toLong() * k)
        f == Frequency.CUSTOM -> anchor.plusDays(customDays.coerceAtLeast(1).toLong() * k)
        else -> anchor.plusDays(f.days.toLong() * k)
    }

    /** First occurrence strictly after [after], or null for one-time items. */
    fun nextAfter(anchor: LocalDate, f: Frequency, customDays: Int, after: LocalDate): LocalDate? {
        for (k in 0..MAX_ITERATIONS) {
            val d = occurrence(anchor, f, customDays, k) ?: return null
            if (d.isAfter(after)) return d
        }
        return null
    }

    /** All occurrences within [from, toInclusive]. */
    fun between(anchor: LocalDate, f: Frequency, customDays: Int, from: LocalDate, toInclusive: LocalDate, limit: Int = 1_000): List<LocalDate> {
        val result = ArrayList<LocalDate>()
        for (k in 0..MAX_ITERATIONS) {
            val d = occurrence(anchor, f, customDays, k) ?: break
            if (d.isAfter(toInclusive) || result.size >= limit) break
            if (!d.isBefore(from)) result += d
        }
        return result
    }
}
