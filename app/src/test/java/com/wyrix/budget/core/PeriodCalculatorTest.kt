package com.wyrix.budget.core

import com.wyrix.budget.core.time.PeriodCalculator
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class PeriodCalculatorTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-11-08T10:00:00Z")

    private fun millis(y: Int, m: Int, d: Int) =
        ZonedDateTime.of(y, m, d, 0, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun thisMonthSpansFirstOfMonthToFirstOfNext() {
        val r = PeriodCalculator.thisMonth(now, zone)
        assertEquals(millis(2026, 11, 1), r.fromMillis)
        assertEquals(millis(2026, 12, 1), r.toMillis)
    }

    @Test fun thisMonthHandlesDecemberRollover() {
        val r = PeriodCalculator.thisMonth(Instant.parse("2026-12-20T10:00:00Z"), zone)
        assertEquals(millis(2026, 12, 1), r.fromMillis)
        assertEquals(millis(2027, 1, 1), r.toMillis)
    }

    @Test fun thisYearSpansJanuaryToNextJanuary() {
        val r = PeriodCalculator.thisYear(now, zone)
        assertEquals(millis(2026, 1, 1), r.fromMillis)
        assertEquals(millis(2027, 1, 1), r.toMillis)
    }

    @Test fun last30DaysIncludesTodayAndStartsTwentyNineDaysBack() {
        val r = PeriodCalculator.last30Days(now, zone)
        assertEquals(millis(2026, 10, 10), r.fromMillis)
        assertEquals(millis(2026, 11, 9), r.toMillis)
    }

    @Test fun localMidnightBoundaryUsesTheUsersZone() {
        // 20:00 UTC on 30 Nov is already 1:30 on 1 Dec in India.
        val r = PeriodCalculator.thisMonth(Instant.parse("2026-11-30T20:00:00Z"), zone)
        assertEquals(millis(2026, 12, 1), r.fromMillis)
    }
}
