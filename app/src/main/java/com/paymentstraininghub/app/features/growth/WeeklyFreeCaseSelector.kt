// Picks this week's free payment case, deterministically: the
// same week and the same list always pick the same case, on every
// device, with no server and no stored rotation. Ported from
// WeeklyFreeCaseSelector in ReferralInvite.swift (iOS).
//
// The primary list is the dedicated 52-case weekly list, in week
// order. The pick is the week ordinal (ISO year times 53 plus ISO
// week) modulo 52, so the cases come one after the other, one per
// week, changing on Monday, and the cycle runs continuously across
// year boundaries: it does not reset on 1 January, and a 53-week
// ISO year simply advances the ordinal by one more week, so the
// next case in the sequence follows. After 52 weeks the cycle wraps
// to the first case again. The same function also serves the
// catalogue fallback, with the same ordinal modulo the catalogue
// length, for the weeks when the weekly list has not loaded. Two
// honest limits, as on iOS: the pick depends on the order of the
// ids passed in, so callers must always pass a stable order
// (weekly list in week order, or catalogue order); and when the
// fallback catalogue grows, later weeks may pick different cases
// than they would have before, because the list length changed.
// Neither limit invents a case: the pick is always one real id
// from the list passed in, or null when the list is empty.
// No em dashes.
package com.paymentstraininghub.app.features.growth

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.util.Locale

object WeeklyFreeCaseSelector {

    private val isoFields: WeekFields = WeekFields.ISO

    /** The ISO week-numbering year and ISO week number (1 to 53) for a date. */
    fun weekComponents(date: LocalDate): Pair<Int, Int> {
        val year = date.get(isoFields.weekBasedYear())
        val week = date.get(isoFields.weekOfWeekBasedYear())
        return year to week
    }

    /** The ISO week components for a moment, in the device time zone, matching the iOS ISO calendar. */
    fun weekComponents(timeMillis: Long): Pair<Int, Int> =
        weekComponents(Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDate())

    /**
     * The week key for a date, for example "2026-W46": the ISO year
     * for the week plus the ISO week number. Used as the storage key
     * for the opened marker, so one marker per week.
     */
    fun weekKey(date: LocalDate = LocalDate.now()): String {
        val (year, week) = weekComponents(date)
        return "$year-W${week.toString().padStart(2, '0')}"
    }

    /** The week key for a moment. */
    fun weekKey(timeMillis: Long): String = weekKey(
        Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    )

    /**
     * The index picked for a week, or null when the list is empty.
     * The week ordinal (ISO year times 53 plus ISO week) modulo the
     * list length. Over the 52-case weekly list this is (ISO year
     * times 53 plus ISO week) mod 52: one case per week in
     * continuous sequence across year boundaries, wrapping after
     * 52 weeks. Stable for a fixed list, and it advances by one
     * each week until it wraps.
     */
    fun index(date: LocalDate, count: Int): Int? {
        if (count <= 0) return null
        val (year, week) = weekComponents(date)
        val ordinal = year * 53 + week
        return ((ordinal % count) + count) % count
    }

    /** The index picked for the week containing a moment. */
    fun index(timeMillis: Long, count: Int): Int? =
        index(Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDate(), count)

    /** The situation id picked for a week, from ids in stable order. Null when the list is empty. */
    fun situationId(ids: List<String>, date: LocalDate = LocalDate.now()): String? {
        val picked = index(date, ids.size) ?: return null
        return ids[picked]
    }

    /** The situation id picked for the week containing a moment. */
    fun situationId(ids: List<String>, timeMillis: Long): String? {
        val picked = index(timeMillis, ids.size) ?: return null
        return ids[picked]
    }

    /** The Monday to Sunday range of the week containing a date. */
    fun weekRange(date: LocalDate = LocalDate.now()): Pair<LocalDate, LocalDate> {
        val monday = date.with(isoFields.dayOfWeek(), 1L)
        return monday to monday.plusDays(6)
    }

    /**
     * The label for the week containing a date, for example
     * "Week of 9 Nov 2026". Built from the week start only, so it
     * never depends on the device time zone shifting the end date.
     */
    fun weekLabel(date: LocalDate = LocalDate.now()): String {
        val (start, _) = weekRange(date)
        val formatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
        return "Week of ${start.format(formatter)}"
    }

    /** The label for the week containing a moment. */
    fun weekLabel(timeMillis: Long): String = weekLabel(
        Instant.ofEpochMilli(timeMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    )
}
