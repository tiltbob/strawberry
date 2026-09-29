package io.github.tiltbob.strawberry.schedule

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.TUESDAY
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class ScheduleTest {
    private val newYork = ZoneId.of("America/New_York")
    private val london = ZoneId.of("Europe/London")
    private val lordHowe = ZoneId.of("Australia/Lord_Howe")
    private val havana = ZoneId.of("America/Havana")
    private val kolkata = ZoneId.of("Asia/Kolkata")

    private val officeHours = schedule(9, 0, 18, 0, Schedule.WEEKDAYS)

    private fun schedule(fromH: Int, fromM: Int, toH: Int, toM: Int, days: Set<DayOfWeek>) =
        Schedule(enabled = true, start = LocalTime.of(fromH, fromM), end = LocalTime.of(toH, toM), days = days)

    /** A local time that exists exactly once in [zone]. */
    private fun at(zone: ZoneId, local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    @Test
    fun defaultIsDisabledOfficeHoursOnWeekdays() {
        val default = Schedule()
        assertFalse(default.enabled)
        assertEquals(LocalTime.of(9, 0), default.start)
        assertEquals(LocalTime.of(18, 0), default.end)
        assertEquals(setOf(MONDAY, TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, FRIDAY), default.days)
    }

    @Test
    fun nextChangeSkipsTheBoundaryThatMatchesAManualChange() {
        // 2026-10-06 is a Tuesday.
        val tuesdayEvening = at(newYork, "2026-10-06T20:00")
        val tuesdayMorning = at(newYork, "2026-10-06T10:00")

        // Turned on by hand: 09:00 changes nothing, so the next change is the pause at 18:00.
        assertEquals(at(newYork, "2026-10-07T18:00"), officeHours.nextChange(tuesdayEvening, newYork, workOn = true))
        // Paused by hand: 18:00 changes nothing, so the next change is 09:00 the next day.
        assertEquals(at(newYork, "2026-10-07T09:00"), officeHours.nextChange(tuesdayMorning, newYork, workOn = false))
        // Following the schedule, or unknown: the plain next transition.
        assertEquals(at(newYork, "2026-10-07T09:00"), officeHours.nextChange(tuesdayEvening, newYork, workOn = false))
        assertEquals(at(newYork, "2026-10-07T09:00"), officeHours.nextChange(tuesdayEvening, newYork, workOn = null))
        assertNull(schedule(9, 0, 18, 0, emptySet()).nextChange(tuesdayEvening, newYork, workOn = true))
    }

    @Test
    fun officeHoursWindowIsHalfOpen() {
        // 2026-10-05 is a Monday.
        assertFalse(officeHours.isWorkOnAt(at(newYork, "2026-10-05T08:59"), newYork))
        assertTrue(officeHours.isWorkOnAt(at(newYork, "2026-10-05T09:00"), newYork))
        assertTrue(officeHours.isWorkOnAt(at(newYork, "2026-10-05T17:59"), newYork))
        assertFalse(officeHours.isWorkOnAt(at(newYork, "2026-10-05T18:00"), newYork))
        assertFalse(officeHours.isWorkOnAt(at(newYork, "2026-10-10T12:00"), newYork))
    }

    @Test
    fun officeHoursTransitions() {
        assertEquals(
            at(newYork, "2026-10-05T09:00"),
            officeHours.nextTransition(at(newYork, "2026-10-05T08:00"), newYork),
        )
        // Strictly after: at exactly 09:00 the next change is the end of the window.
        assertEquals(
            at(newYork, "2026-10-05T18:00"),
            officeHours.nextTransition(at(newYork, "2026-10-05T09:00"), newYork),
        )
        // Friday evening to Monday morning.
        assertEquals(
            at(newYork, "2026-10-12T09:00"),
            officeHours.nextTransition(at(newYork, "2026-10-09T18:00"), newYork),
        )
    }

    @Test
    fun overnightWindowEndsTheNextDay() {
        val nights = schedule(22, 0, 6, 0, setOf(FRIDAY))
        assertTrue(nights.endsNextDay)
        assertFalse(nights.isWorkOnAt(at(newYork, "2026-10-09T21:59"), newYork))
        assertTrue(nights.isWorkOnAt(at(newYork, "2026-10-09T22:00"), newYork))
        assertTrue(nights.isWorkOnAt(at(newYork, "2026-10-10T05:59"), newYork))
        assertFalse(nights.isWorkOnAt(at(newYork, "2026-10-10T06:00"), newYork))
        // Thursday is not selected, so Thursday night to Friday morning stays paused.
        assertFalse(nights.isWorkOnAt(at(newYork, "2026-10-09T02:00"), newYork))
        assertEquals(
            at(newYork, "2026-10-10T06:00"),
            nights.nextTransition(at(newYork, "2026-10-09T23:00"), newYork),
        )
        assertEquals(
            at(newYork, "2026-10-16T22:00"),
            nights.nextTransition(at(newYork, "2026-10-10T06:00"), newYork),
        )
    }

    @Test
    fun startEqualToEndIsA24HourWindowAndAdjacentWindowsMerge() {
        val twoDays = schedule(9, 0, 9, 0, setOf(MONDAY, TUESDAY))
        assertTrue(twoDays.isWorkOnAt(at(newYork, "2026-10-06T08:59"), newYork))
        assertTrue(twoDays.isWorkOnAt(at(newYork, "2026-10-07T08:59"), newYork))
        assertFalse(twoDays.isWorkOnAt(at(newYork, "2026-10-07T09:00"), newYork))
        // Tuesday 09:00 changes nothing, so it is skipped.
        assertEquals(
            at(newYork, "2026-10-07T09:00"),
            twoDays.nextTransition(at(newYork, "2026-10-05T10:00"), newYork),
        )
    }

    @Test
    fun noDaysMeansAlwaysPausedAndNoTransitions() {
        val none = schedule(9, 0, 18, 0, emptySet())
        val now = at(newYork, "2026-10-05T12:00")
        assertFalse(none.isWorkOnAt(now, newYork))
        assertNull(none.nextTransition(now, newYork))
    }

    @Test
    fun everyDayAroundTheClockMeansAlwaysOnAndNoTransitions() {
        val always = schedule(0, 0, 0, 0, DayOfWeek.entries.toSet())
        val now = at(newYork, "2026-10-05T03:00")
        assertTrue(always.isWorkOnAt(now, newYork))
        assertNull(always.nextTransition(now, newYork))
    }

    @Test
    fun newYorkSpringForwardGapResolvesToEndOfGap() {
        // 2026-03-08: 02:00 EST jumps to 03:00 EDT (07:00Z).
        val gapEnd = Instant.parse("2026-03-08T07:00:00Z")
        assertEquals(gapEnd, Schedule.resolve(LocalDate.of(2026, 3, 8), LocalTime.of(2, 30), newYork))

        val night = schedule(1, 30, 2, 30, setOf(SUNDAY))
        val start = at(newYork, "2026-03-08T01:30")
        assertEquals(gapEnd, night.nextTransition(start, newYork))
        assertTrue(night.isWorkOnAt(gapEnd.minusSeconds(60), newYork))
        assertFalse(night.isWorkOnAt(gapEnd, newYork))
    }

    @Test
    fun newYorkFallBackOverlapUsesEarlierInstant() {
        // 2026-11-01: 01:30 happens at 05:30Z (EDT) and again at 06:30Z (EST).
        val night = schedule(1, 30, 2, 30, setOf(SUNDAY))
        val start = Schedule.resolve(LocalDate.of(2026, 11, 1), LocalTime.of(1, 30), newYork)
        assertEquals(Instant.parse("2026-11-01T05:30:00Z"), start)
        assertTrue(night.isWorkOnAt(Instant.parse("2026-11-01T06:45:00Z"), newYork))
        // 02:30 EST = 07:30Z, so the window is two real hours long this night.
        assertEquals(Instant.parse("2026-11-01T07:30:00Z"), night.nextTransition(start, newYork))
    }

    @Test
    fun officeHoursKeepLocalTimeAcrossDst() {
        // The weekend of 2026-03-08 moves New York from UTC-5 to UTC-4.
        assertEquals(
            Instant.parse("2026-03-09T13:00:00Z"),
            officeHours.nextTransition(at(newYork, "2026-03-06T18:00"), newYork),
        )
    }

    @Test
    fun londonGapAndOverlap() {
        // 2026-03-29: 01:00 GMT jumps to 02:00 BST (01:00Z).
        assertEquals(
            Instant.parse("2026-03-29T01:00:00Z"),
            Schedule.resolve(LocalDate.of(2026, 3, 29), LocalTime.of(1, 30), london),
        )
        // 2026-10-25: 01:30 happens at 00:30Z (BST) and 01:30Z (GMT).
        assertEquals(
            Instant.parse("2026-10-25T00:30:00Z"),
            Schedule.resolve(LocalDate.of(2026, 10, 25), LocalTime.of(1, 30), london),
        )
        val sundayNight = schedule(23, 0, 1, 30, setOf(SATURDAY))
        assertEquals(
            Instant.parse("2026-10-25T00:30:00Z"),
            sundayNight.nextTransition(Instant.parse("2026-10-24T23:00:00Z"), london),
        )
    }

    @Test
    fun lordHoweHalfHourDst() {
        // 2026-10-04: 02:00 (+10:30) jumps to 02:30 (+11:00), at 2026-10-03T15:30Z.
        assertEquals(
            Instant.parse("2026-10-03T15:30:00Z"),
            Schedule.resolve(LocalDate.of(2026, 10, 4), LocalTime.of(2, 15), lordHowe),
        )
        // 2026-04-05: 02:00 (+11:00) falls back to 01:30 (+10:30); 01:45 happens twice.
        assertEquals(
            Instant.parse("2026-04-04T14:45:00Z"),
            Schedule.resolve(LocalDate.of(2026, 4, 5), LocalTime.of(1, 45), lordHowe),
        )
        val sunday = schedule(2, 15, 2, 45, setOf(SUNDAY))
        // On the spring-forward Sunday the window is only 02:30-02:45 (15 real minutes).
        val start = sunday.nextTransition(Instant.parse("2026-10-03T12:00:00Z"), lordHowe)
        assertEquals(Instant.parse("2026-10-03T15:30:00Z"), start)
        assertEquals(Instant.parse("2026-10-03T15:45:00Z"), sunday.nextTransition(start!!, lordHowe))
    }

    @Test
    fun havanaMidnightDst() {
        // 2026-03-08: midnight (-05:00) jumps to 01:00 (-04:00), so Sunday starts at 05:00Z.
        val sundayMorning = schedule(0, 0, 8, 0, setOf(SUNDAY))
        val saturdayNoon = at(havana, "2026-03-07T12:00")
        assertEquals(
            Instant.parse("2026-03-08T05:00:00Z"),
            sundayMorning.nextTransition(saturdayNoon, havana),
        )
        assertTrue(sundayMorning.isWorkOnAt(Instant.parse("2026-03-08T05:00:00Z"), havana))
        assertEquals(
            Instant.parse("2026-03-08T12:00:00Z"),
            sundayMorning.nextTransition(Instant.parse("2026-03-08T05:00:00Z"), havana),
        )
        // 2026-11-01: 01:00 (-04:00) falls back to midnight (-05:00); the earlier midnight wins.
        assertEquals(
            Instant.parse("2026-11-01T04:00:00Z"),
            Schedule.resolve(LocalDate.of(2026, 11, 1), LocalTime.MIDNIGHT, havana),
        )
    }

    @Test
    fun kolkataHasNoDst() {
        val friday = at(kolkata, "2026-03-27T18:00")
        assertEquals(Instant.parse("2026-03-30T03:30:00Z"), officeHours.nextTransition(friday, kolkata))
        assertEquals(
            Instant.parse("2026-10-26T03:30:00Z"),
            officeHours.nextTransition(at(kolkata, "2026-10-24T12:00"), kolkata),
        )
    }

    @Test
    fun isWorkOnAtChangesExactlyAtNextTransition() {
        val zones = listOf(newYork, london, lordHowe, havana, kolkata, ZoneId.of("Australia/Sydney"))
        val schedules = listOf(
            officeHours,
            schedule(22, 0, 6, 0, setOf(FRIDAY, SATURDAY)),
            schedule(9, 0, 9, 0, setOf(MONDAY, TUESDAY)),
            schedule(1, 30, 2, 30, DayOfWeek.entries.toSet()),
            schedule(0, 0, 8, 0, setOf(SUNDAY)),
            schedule(2, 0, 2, 15, DayOfWeek.entries.toSet()),
            schedule(0, 0, 0, 0, DayOfWeek.entries.toSet() - DayOfWeek.WEDNESDAY),
            schedule(23, 45, 0, 15, Schedule.WEEKDAYS),
        )
        val from = Instant.parse("2026-01-01T00:00:00Z")
        val until = Instant.parse("2027-01-01T00:00:00Z")
        val step = 15L * 60
        for (zone in zones) for (schedule in schedules) {
            val label = "$schedule in $zone"
            var t = from
            var state = schedule.isWorkOnAt(t, zone)
            var next = schedule.nextTransition(t, zone)
            var steps = 0
            while (t < until) {
                val t2 = t.plusSeconds(step)
                if (next != null && next <= t2) {
                    assertTrue("$label: $next is after $t", next > t)
                    assertEquals("$label: unchanged just before $next", state, schedule.isWorkOnAt(next.minusSeconds(1), zone))
                    assertEquals("$label: changes at $next", !state, schedule.isWorkOnAt(next, zone))
                    t = next
                    state = !state
                    next = schedule.nextTransition(t, zone)
                } else {
                    assertEquals("$label: no change until $t2", state, schedule.isWorkOnAt(t2, zone))
                    t = t2
                }
                // From any point in between, the answer must be the same next transition.
                if (steps++ % 97 == 0) {
                    assertEquals("$label: consistent at $t", next, schedule.nextTransition(t, zone))
                }
            }
        }
    }
}
