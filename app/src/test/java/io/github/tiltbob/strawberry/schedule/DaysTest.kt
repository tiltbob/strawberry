package io.github.tiltbob.strawberry.schedule

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.SATURDAY
import java.time.DayOfWeek.SUNDAY
import java.time.DayOfWeek.THURSDAY
import java.time.DayOfWeek.TUESDAY
import java.time.DayOfWeek.WEDNESDAY

class DaysTest {
    @Test
    fun weekFollowsTheLocaleStartDay() {
        assertEquals(listOf(SUNDAY, MONDAY, TUESDAY, WEDNESDAY, THURSDAY, FRIDAY, SATURDAY), weekStartingOn(SUNDAY))
        assertEquals(MONDAY, weekStartingOn(MONDAY).first())
    }

    @Test
    fun weekdaysAreOneRun() {
        assertEquals(listOf(Schedule.WEEKDAYS.sorted()), consecutiveRuns(Schedule.WEEKDAYS, MONDAY))
    }

    @Test
    fun gapsSplitRuns() {
        assertEquals(
            listOf(listOf(MONDAY), listOf(WEDNESDAY), listOf(FRIDAY, SATURDAY)),
            consecutiveRuns(setOf(MONDAY, WEDNESDAY, FRIDAY, SATURDAY), MONDAY),
        )
    }

    @Test
    fun runsDoNotWrapAroundTheWeek() {
        assertEquals(
            listOf(listOf(MONDAY), listOf(SATURDAY, SUNDAY)),
            consecutiveRuns(setOf(SATURDAY, SUNDAY, MONDAY), MONDAY),
        )
        assertEquals(
            listOf(listOf(SUNDAY, MONDAY), listOf(SATURDAY)),
            consecutiveRuns(setOf(SATURDAY, SUNDAY, MONDAY), SUNDAY),
        )
    }

    @Test
    fun noDaysNoRuns() {
        assertEquals(emptyList<Any>(), consecutiveRuns(emptySet(), MONDAY))
    }
}
