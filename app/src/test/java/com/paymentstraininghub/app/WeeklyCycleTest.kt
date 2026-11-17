package com.paymentstraininghub.app

import com.paymentstraininghub.app.features.growth.WeeklyFreeCaseSelector
import com.paymentstraininghub.app.features.investigation.WeeklyCaseSelector
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Weekly-cycle rule tests: the index is (ISO year x 53 + ISO
 * week) mod list length, one case per week in continuous
 * sequence, wrapping after 52 weeks. The known vector is the
 * week of 9 Nov 2026 (2026-W46), which picks index 44 of 52
 * (the 45th case, WC-45).
 */
class WeeklyCycleTest {

    @Test
    fun weekKey_formatsIsoYearAndWeek() {
        assertEquals("2026-W46", WeeklyFreeCaseSelector.weekKey(LocalDate.of(2026, 11, 9)))
        assertEquals("2026-W46", WeeklyFreeCaseSelector.weekKey(LocalDate.of(2026, 11, 15)))
        assertEquals("2026-W47", WeeklyFreeCaseSelector.weekKey(LocalDate.of(2026, 11, 16)))
    }

    @Test
    fun weekComponents_useIsoWeekNumbering() {
        // 29 Dec 2025 is a Monday and belongs to 2026-W01.
        assertEquals(
            2026 to 1,
            WeeklyFreeCaseSelector.weekComponents(LocalDate.of(2025, 12, 29))
        )
        // 28 Dec 2025 is the Sunday ending 2025-W52.
        assertEquals(
            2025 to 52,
            WeeklyFreeCaseSelector.weekComponents(LocalDate.of(2025, 12, 28))
        )
    }

    @Test
    fun index_knownVectorForWeekOf9Nov2026() {
        assertEquals(44, WeeklyFreeCaseSelector.index(LocalDate.of(2026, 11, 9), 52))
        assertEquals(44, WeeklyFreeCaseSelector.index(LocalDate.of(2026, 11, 15), 52))
    }

    @Test
    fun index_advancesOnePerWeek() {
        assertEquals(45, WeeklyFreeCaseSelector.index(LocalDate.of(2026, 11, 16), 52))
        assertEquals(46, WeeklyFreeCaseSelector.index(LocalDate.of(2026, 11, 23), 52))
    }

    @Test
    fun index_yearBoundaryStep() {
        // The year term steps by 53 at a year boundary, so the
        // index steps by 2 when the old year had 52 weeks.
        assertEquals(49, WeeklyFreeCaseSelector.index(LocalDate.of(2025, 12, 28), 52))
        assertEquals(51, WeeklyFreeCaseSelector.index(LocalDate.of(2025, 12, 29), 52))
    }

    @Test
    fun index_returnsNullForEmptyList() {
        assertNull(WeeklyFreeCaseSelector.index(LocalDate.of(2026, 11, 9), 0))
        assertNull(WeeklyFreeCaseSelector.index(LocalDate.of(2026, 11, 9), -3))
    }

    @Test
    fun index_wrapsAfter52Weeks() {
        // 52 weeks after 9 Nov 2026 the cycle returns to the same
        // index: the ordinal advances by 52 x 53, a multiple of 52.
        val first = WeeklyFreeCaseSelector.index(LocalDate.of(2026, 11, 9), 52)
        val later = WeeklyFreeCaseSelector.index(LocalDate.of(2027, 11, 8), 52)
        assertEquals(first, later)
    }

    @Test
    fun situationId_picksFromListInOrder() {
        val ids = (1..52).map { "WC-%02d".format(it) }
        assertEquals(
            "WC-45",
            WeeklyFreeCaseSelector.situationId(ids, LocalDate.of(2026, 11, 9))
        )
        assertNull(WeeklyFreeCaseSelector.situationId(emptyList(), LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun weeklyCaseSelector_matchesTheSameRule() {
        // Midday UTC on 9 Nov 2026 stays inside 2026-W46 in every
        // real time zone, so this holds whatever the device zone is.
        val millis = Instant.parse("2026-11-09T12:00:00Z").toEpochMilli()
        assertEquals(44, WeeklyCaseSelector.index(52, millis))
        assertEquals("2026-W46", WeeklyCaseSelector.weekKey(millis))
        assertNull(WeeklyCaseSelector.index(0, millis))
    }
}
