package io.github.tiltbob.strawberry.platform

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import androidx.core.content.edit
import io.github.tiltbob.strawberry.core.ApplyResult
import io.github.tiltbob.strawberry.core.Reason
import io.github.tiltbob.strawberry.core.RunStatus
import io.github.tiltbob.strawberry.core.ScheduleStore
import io.github.tiltbob.strawberry.schedule.Schedule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * Everything the app remembers, in one SharedPreferences file. Writes use commit() because they
 * happen on the background thread, often right before a broadcast finishes. The one exception is
 * [askedForNotifications], which the screen writes on the main thread.
 */
class Prefs(context: Context) : ScheduleStore {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("state", Context.MODE_PRIVATE)

    override var schedule: Schedule
        get() {
            val default = Schedule()
            return Schedule(
                enabled = prefs.getBoolean(ENABLED, default.enabled),
                start = minuteToTime(prefs.getInt(START, timeToMinute(default.start))),
                end = minuteToTime(prefs.getInt(END, timeToMinute(default.end))),
                days = maskToDays(prefs.getInt(DAYS, daysToMask(default.days))),
            )
        }
        set(value) = prefs.edit(commit = true) {
            putBoolean(ENABLED, value.enabled)
            putInt(START, timeToMinute(value.start))
            putInt(END, timeToMinute(value.end))
            putInt(DAYS, daysToMask(value.days))
        }

    override var lastDesiredWorkOn: Boolean?
        get() = if (prefs.contains(LAST_DESIRED)) prefs.getBoolean(LAST_DESIRED, false) else null
        set(value) = prefs.edit(commit = true) {
            if (value == null) remove(LAST_DESIRED) else putBoolean(LAST_DESIRED, value)
        }

    override var status: RunStatus?
        get() {
            val reason = prefs.getString(STATUS_REASON, null)
                ?.let { name -> Reason.entries.firstOrNull { it.name == name } }
                ?: return null
            return RunStatus(
                lastRunAt = Instant.ofEpochMilli(prefs.getLong(STATUS_RUN_AT, 0)),
                lastReason = reason,
                lastApplyAt = prefs.getInstant(STATUS_APPLY_AT),
                lastApplyResult = decodeResult(
                    prefs.getString(STATUS_RESULT, null),
                    prefs.getString(STATUS_MESSAGE, null),
                ),
                nextAlarmAt = prefs.getInstant(STATUS_NEXT_ALARM),
                nextAlarmExact = prefs.getBoolean(STATUS_EXACT, true),
            )
        }
        set(value) = prefs.edit(commit = true) {
            if (value == null) {
                STATUS_KEYS.forEach(::remove)
                return@edit
            }
            putLong(STATUS_RUN_AT, value.lastRunAt.toEpochMilli())
            putString(STATUS_REASON, value.lastReason.name)
            putInstant(STATUS_APPLY_AT, value.lastApplyAt)
            putString(STATUS_RESULT, value.lastApplyResult?.let(::resultCode))
            putString(STATUS_MESSAGE, (value.lastApplyResult as? ApplyResult.Error)?.message)
            putInstant(STATUS_NEXT_ALARM, value.nextAlarmAt)
            putBoolean(STATUS_EXACT, value.nextAlarmExact)
        }

    /** Serial number of the chosen work profile. */
    val selectedProfileSerial: Long?
        get() = if (prefs.contains(PROFILE_SERIAL)) prefs.getLong(PROFILE_SERIAL, -1) else null

    /** True if the user explicitly picked [selectedProfileSerial] (needed for unidentified ones). */
    val selectedProfileConfirmed: Boolean get() = prefs.getBoolean(PROFILE_CONFIRMED, false)

    fun selectProfile(serial: Long, confirmedByUser: Boolean) {
        val changed = serial != selectedProfileSerial
        prefs.edit(commit = true) {
            putLong(PROFILE_SERIAL, serial)
            putBoolean(PROFILE_CONFIRMED, confirmedByUser)
            // The schedule has not been applied to this profile yet, so the next run applies it.
            if (changed) remove(LAST_DESIRED)
        }
    }

    fun clearProfile() = prefs.edit(commit = true) {
        remove(PROFILE_SERIAL)
        remove(PROFILE_CONFIRMED)
    }

    /** Serials the system itself reported as managed profiles (from the availability broadcasts). */
    val learnedManagedSerials: Set<Long>
        get() = prefs.getStringSet(LEARNED, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun learnManagedSerial(serial: Long) {
        val learned = learnedManagedSerials
        if (serial in learned) return
        prefs.edit(commit = true) { putStringSet(LEARNED, (learned + serial).map { it.toString() }.toSet()) }
    }

    /**
     * The phone's boot count when BOOT_COMPLETED last arrived or the app was first opened, or -1
     * if unknown.
     */
    var lastBootCount: Int
        get() = prefs.getInt(BOOT_COUNT, -1)
        set(value) = prefs.edit(commit = true) { putInt(BOOT_COUNT, value) }

    /**
     * Stores [count] only if no boot count is stored yet. Never overwrites: if the app is opened
     * right after a reboot, before BOOT_COMPLETED is handled, that reboot must still count.
     */
    fun rememberBootCountIfUnknown(count: Int) {
        if (count != -1 && lastBootCount == -1) lastBootCount = count
    }

    /** Whether the app has asked for the notification permission before. */
    var askedForNotifications: Boolean
        get() = prefs.getBoolean(ASKED_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(ASKED_NOTIFICATIONS, value) }

    private companion object {
        const val ENABLED = "schedule_enabled"
        const val START = "schedule_start_minute"
        const val END = "schedule_end_minute"
        const val DAYS = "schedule_days"
        const val LAST_DESIRED = "last_desired_work_on"
        const val PROFILE_SERIAL = "profile_serial"
        const val PROFILE_CONFIRMED = "profile_confirmed"
        const val LEARNED = "learned_managed_serials"
        const val ASKED_NOTIFICATIONS = "asked_for_notifications"
        const val BOOT_COUNT = "last_boot_count"
        const val STATUS_RUN_AT = "status_run_at"
        const val STATUS_REASON = "status_reason"
        const val STATUS_APPLY_AT = "status_apply_at"
        const val STATUS_RESULT = "status_result"
        const val STATUS_MESSAGE = "status_message"
        const val STATUS_NEXT_ALARM = "status_next_alarm"
        const val STATUS_EXACT = "status_exact"
        val STATUS_KEYS = listOf(
            STATUS_RUN_AT, STATUS_REASON, STATUS_APPLY_AT, STATUS_RESULT, STATUS_MESSAGE,
            STATUS_NEXT_ALARM, STATUS_EXACT,
        )

        fun timeToMinute(time: LocalTime) = time.hour * 60 + time.minute
        fun minuteToTime(minute: Int): LocalTime =
            LocalTime.of((minute / 60).coerceIn(0, 23), (minute % 60).coerceIn(0, 59))

        fun daysToMask(days: Set<DayOfWeek>) = days.fold(0) { mask, day -> mask or (1 shl day.ordinal) }
        fun maskToDays(mask: Int) = DayOfWeek.entries.filter { mask and (1 shl it.ordinal) != 0 }.toSet()

        fun resultCode(result: ApplyResult): String = when (result) {
            ApplyResult.Ok -> "OK"
            ApplyResult.Already -> "ALREADY"
            ApplyResult.NeedsCredential -> "NEEDS_CREDENTIAL"
            ApplyResult.NoPermission -> "NO_PERMISSION"
            ApplyResult.ProfileMissing -> "PROFILE_MISSING"
            ApplyResult.ProfileUnconfirmed -> "PROFILE_UNCONFIRMED"
            ApplyResult.PauseRefused -> "PAUSE_REFUSED"
            is ApplyResult.Error -> "ERROR"
        }

        fun decodeResult(code: String?, message: String?): ApplyResult? = when (code) {
            "OK" -> ApplyResult.Ok
            "ALREADY" -> ApplyResult.Already
            "NEEDS_CREDENTIAL" -> ApplyResult.NeedsCredential
            "NO_PERMISSION" -> ApplyResult.NoPermission
            "PROFILE_MISSING" -> ApplyResult.ProfileMissing
            "PROFILE_UNCONFIRMED" -> ApplyResult.ProfileUnconfirmed
            "PAUSE_REFUSED" -> ApplyResult.PauseRefused
            "ERROR" -> ApplyResult.Error(message.orEmpty())
            else -> null
        }

        fun SharedPreferences.getInstant(key: String): Instant? =
            if (contains(key)) Instant.ofEpochMilli(getLong(key, 0)) else null

        fun SharedPreferences.Editor.putInstant(key: String, value: Instant?) {
            if (value == null) remove(key) else putLong(key, value.toEpochMilli())
        }
    }
}

/** How often the phone has booted, or -1 if that cannot be read. */
fun bootCount(context: Context): Int =
    Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
