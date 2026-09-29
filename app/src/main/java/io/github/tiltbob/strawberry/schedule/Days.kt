package io.github.tiltbob.strawberry.schedule

import java.time.DayOfWeek

/** The seven days in the order a week is shown in, starting at [firstDay]. */
fun weekStartingOn(firstDay: DayOfWeek): List<DayOfWeek> = (0L..6L).map { firstDay.plus(it) }

/**
 * Groups [days] into runs of consecutive days in the week starting at [firstDay], for summaries
 * such as "Mon–Fri" or "Mon, Wed, Fri". Runs do not wrap around the end of the week.
 */
fun consecutiveRuns(days: Set<DayOfWeek>, firstDay: DayOfWeek): List<List<DayOfWeek>> {
    val runs = mutableListOf<MutableList<DayOfWeek>>()
    var previousSelected = false
    for (day in weekStartingOn(firstDay)) {
        val selected = day in days
        if (selected) {
            if (previousSelected) runs.last() += day else runs += mutableListOf(day)
        }
        previousSelected = selected
    }
    return runs
}
