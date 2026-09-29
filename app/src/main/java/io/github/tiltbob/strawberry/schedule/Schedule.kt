package io.github.tiltbob.strawberry.schedule

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/**
 * A weekly schedule with one "work apps on" window. Work apps are paused at all other times.
 *
 * - [days] are the local days on which a window STARTS.
 * - The window is half-open: `[start, end)`.
 * - If `end <= start` the window ends on the next day, so `start == end` is a 24 hour window
 *   (the same convention as the Do Not Disturb schedules in Android).
 */
data class Schedule(
    val enabled: Boolean = false,
    val start: LocalTime = LocalTime.of(9, 0),
    val end: LocalTime = LocalTime.of(18, 0),
    val days: Set<DayOfWeek> = WEEKDAYS,
) {
    init {
        require(start == start.truncatedTo(ChronoUnit.MINUTES)) { "start must be whole minutes" }
        require(end == end.truncatedTo(ChronoUnit.MINUTES)) { "end must be whole minutes" }
    }

    /** True if the window starting on a selected day ends on the following day. */
    val endsNextDay: Boolean get() = end <= start

    /** Whether work apps should be on at [instant]. Ignores [enabled]. */
    fun isWorkOnAt(instant: Instant, zone: ZoneId): Boolean {
        val today = instant.atZone(zone).toLocalDate()
        // A window lasts at most about 25 hours, so only yesterday's or today's can contain it.
        return listOf(today.minusDays(1), today).any { day ->
            day.dayOfWeek in days && window(day, zone).let { (from, until) ->
                !instant.isBefore(from) && instant.isBefore(until)
            }
        }
    }

    /**
     * The first window start or end strictly after [after] at which [isWorkOnAt] actually changes,
     * or null if it never changes (no days selected, or every day with a 24 hour window).
     * Boundaries where nothing changes (such as one 24 hour window running into the next) are
     * skipped. Ignores [enabled].
     */
    fun nextTransition(after: Instant, zone: ZoneId): Instant? {
        if (days.isEmpty()) return null
        val today = after.atZone(zone).toLocalDate()
        val stateNow = isWorkOnAt(after, zone)
        // Window boundaries come in chronological order, so the first candidate with a different
        // state is the answer. Scan about a year: a window can shrink to nothing on a DST day.
        for (offset in -1L..SCAN_DAYS) {
            val day = today.plusDays(offset)
            if (day.dayOfWeek !in days) continue
            val (from, until) = window(day, zone)
            for (candidate in listOf(from, until)) {
                if (candidate.isAfter(after) && isWorkOnAt(candidate, zone) != stateNow) {
                    return candidate
                }
            }
        }
        return null
    }

    /**
     * The first transition after [after] that actually changes work apps when they are currently
     * [workOn] (null if unknown). If they were changed by hand, the next transition only brings
     * the schedule to the state they already have, so the change comes one transition later.
     */
    fun nextChange(after: Instant, zone: ZoneId, workOn: Boolean?): Instant? {
        val next = nextTransition(after, zone) ?: return null
        return if (workOn != null && isWorkOnAt(next, zone) == workOn) nextTransition(next, zone) else next
    }

    private fun window(startDay: LocalDate, zone: ZoneId): Pair<Instant, Instant> {
        val endDay = if (endsNextDay) startDay.plusDays(1) else startDay
        return resolve(startDay, start, zone) to resolve(endDay, end, zone)
    }

    companion object {
        val WEEKDAYS: Set<DayOfWeek> = DayOfWeek.entries.take(5).toSet()
        private const val SCAN_DAYS = 370L

        /**
         * The instant a boundary at local [date] and [time] happens: the first moment whose local
         * time is at or after it. In a DST gap that is the end of the gap; in an overlap it is
         * the earlier of the two moments.
         */
        fun resolve(date: LocalDate, time: LocalTime, zone: ZoneId): Instant {
            val local = LocalDateTime.of(date, time)
            val rules = zone.rules
            return if (rules.getValidOffsets(local).isEmpty()) {
                rules.getTransition(local).instant
            } else {
                ZonedDateTime.ofLocal(local, zone, null).withEarlierOffsetAtOverlap().toInstant()
            }
        }
    }
}
