package com.wyrix.budget.core.time

import java.time.Instant
import java.time.ZoneId

/** Half-open range of epoch milliseconds: from (inclusive) until to (exclusive). */
data class TimeRange(val fromMillis: Long, val toMillis: Long)

object PeriodCalculator {
    fun thisMonth(now: Instant, zone: ZoneId): TimeRange {
        val today = now.atZone(zone).toLocalDate()
        val start = today.withDayOfMonth(1).atStartOfDay(zone)
        return TimeRange(start.toInstant().toEpochMilli(), start.plusMonths(1).toInstant().toEpochMilli())
    }

    fun thisYear(now: Instant, zone: ZoneId): TimeRange {
        val today = now.atZone(zone).toLocalDate()
        val start = today.withDayOfYear(1).atStartOfDay(zone)
        return TimeRange(start.toInstant().toEpochMilli(), start.plusYears(1).toInstant().toEpochMilli())
    }

    /** The last 30 calendar days including today. */
    fun last30Days(now: Instant, zone: ZoneId): TimeRange {
        val today = now.atZone(zone).toLocalDate()
        val start = today.minusDays(29).atStartOfDay(zone)
        val end = today.plusDays(1).atStartOfDay(zone)
        return TimeRange(start.toInstant().toEpochMilli(), end.toInstant().toEpochMilli())
    }

    fun lastMonth(now: Instant, zone: ZoneId): TimeRange {
        val today = now.atZone(zone).toLocalDate()
        val start = today.withDayOfMonth(1).minusMonths(1).atStartOfDay(zone)
        return TimeRange(start.toInstant().toEpochMilli(), start.plusMonths(1).toInstant().toEpochMilli())
    }

    /** Financial year starting in [startMonth] (India: 4 = April). */
    fun financialYear(now: Instant, zone: ZoneId, startMonth: Int, yearsBack: Int = 0): TimeRange {
        val today = now.atZone(zone).toLocalDate()
        val startYear = (if (today.monthValue >= startMonth) today.year else today.year - 1) - yearsBack
        val start = java.time.LocalDate.of(startYear, startMonth, 1).atStartOfDay(zone)
        return TimeRange(start.toInstant().toEpochMilli(), start.plusYears(1).toInstant().toEpochMilli())
    }

    /** Start year of the financial year containing [now]. */
    fun financialYearStart(now: Instant, zone: ZoneId, startMonth: Int): Int {
        val today = now.atZone(zone).toLocalDate()
        return if (today.monthValue >= startMonth) today.year else today.year - 1
    }

    /** The window of equal length immediately before [range], for period-over-period comparison. */
    fun previous(range: TimeRange): TimeRange {
        val length = range.toMillis - range.fromMillis
        return TimeRange(range.fromMillis - length, range.fromMillis)
    }
}
