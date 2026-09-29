package io.github.tiltbob.strawberry.ui

import io.github.tiltbob.strawberry.schedule.Schedule
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "en-rUS")
class FormatsTest {
    private val formats = Formats(RuntimeEnvironment.getApplication())

    /** Newer ICU versions put a narrow no-break space before AM/PM. */
    private fun String.plain() = replace(' ', ' ').replace(' ', ' ')

    @Test
    fun officeHoursSummary() {
        assertEquals(
            "Work apps on Mon–Fri 9:00 AM–6:00 PM. Paused at all other times.",
            formats.summary(Schedule(enabled = true)).plain(),
        )
    }

    @Test
    fun overnightAndAroundTheClockSummaries() {
        val nights = Schedule(start = LocalTime.of(22, 0), end = LocalTime.of(6, 0), days = setOf(DayOfWeek.FRIDAY))
        assertEquals(
            "Work apps on Fri from 10:00 PM until 6:00 AM the next day. Paused at all other times.",
            formats.summary(nights).plain(),
        )
        val allDay = Schedule(start = LocalTime.of(8, 0), end = LocalTime.of(8, 0), days = setOf(DayOfWeek.MONDAY))
        assertEquals(
            "Work apps on Mon from 8:00 AM for 24 hours. Paused at all other times.",
            formats.summary(allDay).plain(),
        )
        val always = allDay.copy(days = DayOfWeek.entries.toSet())
        assertEquals("Work apps always on.", formats.summary(always))
    }

    @Test
    fun noDaysSummary() {
        assertEquals("No days selected, so work apps stay paused.", formats.summary(Schedule(days = emptySet())))
    }

    @Test
    fun daysInLocaleOrder() {
        // The US week starts on Sunday.
        assertEquals(DayOfWeek.SUNDAY, formats.firstDayOfWeek)
        assertEquals("Sun, Sat", formats.days(setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)))
        assertEquals("Mon–Fri", formats.days(Schedule.WEEKDAYS))
        assertEquals("Sun, Mon, Wed", formats.days(setOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY)))
    }

    @Test
    fun moments() {
        val zone = ZoneId.of("America/New_York")
        val now = Instant.parse("2026-10-05T12:00:00Z") // Monday 08:00 in New York
        assertEquals("today at 6:00 PM", formats.moment(Instant.parse("2026-10-05T22:00:00Z"), now, zone).plain())
        assertEquals("tomorrow at 9:00 AM", formats.moment(Instant.parse("2026-10-06T13:00:00Z"), now, zone).plain())
        assertEquals("Fri at 9:00 AM", formats.moment(Instant.parse("2026-10-09T13:00:00Z"), now, zone).plain())
        assertEquals("yesterday at 6:00 PM", formats.moment(Instant.parse("2026-10-04T22:00:00Z"), now, zone).plain())
    }
}
