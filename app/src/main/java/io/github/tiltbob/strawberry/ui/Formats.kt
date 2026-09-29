package io.github.tiltbob.strawberry.ui

import android.content.Context
import android.text.format.DateFormat
import io.github.tiltbob.strawberry.R
import io.github.tiltbob.strawberry.schedule.Schedule
import io.github.tiltbob.strawberry.schedule.consecutiveRuns
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Locale-aware texts for times, days and schedules, following the user's 12/24 hour setting. */
class Formats(private val context: Context) {
    private val locale: Locale = context.resources.configuration.locales[0]
    private val timeFormat = DateFormat.getTimeFormat(context).apply {
        // Times are formatted as UTC wall-clock times so no DST rule can shift them.
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val dateFormatter =
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale)

    val firstDayOfWeek: DayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val is24Hour: Boolean = DateFormat.is24HourFormat(context)

    fun time(time: LocalTime): String =
        timeFormat.format(Date.from(time.atDate(REFERENCE_DATE).toInstant(ZoneOffset.UTC)))

    fun shortDay(day: DayOfWeek): String = day.getDisplayName(TextStyle.SHORT, locale)

    fun fullDay(day: DayOfWeek): String = day.getDisplayName(TextStyle.FULL, locale)

    /** For example "Mon–Fri", "Mon, Wed, Fri" or "every day". */
    fun days(days: Set<DayOfWeek>): String {
        if (days.size == 7) return context.getString(R.string.days_every_day)
        return consecutiveRuns(days, firstDayOfWeek).joinToString(", ") { run ->
            when (run.size) {
                1 -> shortDay(run.single())
                2 -> "${shortDay(run.first())}, ${shortDay(run.last())}"
                else -> context.getString(R.string.days_range, shortDay(run.first()), shortDay(run.last()))
            }
        }
    }

    /** For example "Work apps on Mon–Fri 9:00 AM–6:00 PM. Paused at all other times." */
    fun summary(schedule: Schedule): String {
        if (schedule.days.isEmpty()) return context.getString(R.string.summary_no_days)
        val days = days(schedule.days)
        val start = time(schedule.start)
        return when {
            schedule.start == schedule.end -> context.getString(R.string.summary_24h, days, start)
            schedule.endsNextDay ->
                context.getString(R.string.summary_overnight, days, start, time(schedule.end))
            else -> context.getString(R.string.summary, days, start, time(schedule.end))
        }
    }

    /** For example "today at 6:00 PM", "tomorrow at 9:00 AM" or "Mon at 9:00 AM". */
    fun moment(instant: Instant, now: Instant, zone: ZoneId): String {
        val local = instant.atZone(zone)
        val time = time(local.toLocalTime().truncatedTo(ChronoUnit.MINUTES))
        val days = ChronoUnit.DAYS.between(now.atZone(zone).toLocalDate(), local.toLocalDate())
        return when (days) {
            0L -> context.getString(R.string.moment_today, time)
            1L -> context.getString(R.string.moment_tomorrow, time)
            -1L -> context.getString(R.string.moment_yesterday, time)
            in -6L..6L -> context.getString(R.string.moment_day, shortDay(local.dayOfWeek), time)
            else -> context.getString(R.string.moment_day, dateFormatter.format(local), time)
        }
    }

    private companion object {
        val REFERENCE_DATE: LocalDate = LocalDate.of(2000, 1, 1)
    }
}
