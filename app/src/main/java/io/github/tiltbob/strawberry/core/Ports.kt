package io.github.tiltbob.strawberry.core

import io.github.tiltbob.strawberry.schedule.Schedule
import java.time.Instant

/** Why the [Reconciler] runs. */
enum class Reason {
    ALARM,
    BOOT,
    PACKAGE_REPLACED,
    TIME_CHANGED,
    TIMEZONE_CHANGED,
    EXACT_ALARM_PERMISSION_CHANGED,
    APP_OPENED,
    SCHEDULE_SAVED,
    RETRY,
    ;

    /** Forced runs apply the schedule even if its desired state did not change. */
    val forcesApply: Boolean get() = this == BOOT || this == SCHEDULE_SAVED || this == RETRY

    /** Runs started from the app's own screen, where problems are already visible. */
    val isFromUi: Boolean get() = this == APP_OPENED || this == SCHEDULE_SAVED
}

/** Outcome of trying to put the work profile into the desired state. */
sealed interface ApplyResult {
    /** The profile was paused or unpaused. */
    data object Ok : ApplyResult

    /** The profile was already in the desired state; nothing was called. */
    data object Already : ApplyResult

    /** Unpausing needs the work PIN, so the user has to do it (nothing was shown). */
    data object NeedsCredential : ApplyResult

    /** MODIFY_QUIET_MODE has not been granted. */
    data object NoPermission : ApplyResult

    /** No usable work profile was found (none, or the chosen one is gone). */
    data object ProfileMissing : ApplyResult

    /** Profiles exist but the user has to choose or confirm which one is the work profile. */
    data object ProfileUnconfirmed : ApplyResult

    /** The system declined to pause work apps without saying why. */
    data object PauseRefused : ApplyResult

    /** Anything else; [message] comes from the system and may be empty. */
    data class Error(val message: String) : ApplyResult

    /** Whether the attempt settled the boundary, so it should not be retried. */
    val isDefinitive: Boolean get() = this == Ok || this == Already || this == NeedsCredential
}

/** A small record of the last run, shown on the status card. */
data class RunStatus(
    val lastRunAt: Instant,
    val lastReason: Reason,
    /** When the schedule was last applied, and how that went; null if never. */
    val lastApplyAt: Instant? = null,
    val lastApplyResult: ApplyResult? = null,
    /** The armed boundary alarm, or null if there is none. */
    val nextAlarmAt: Instant? = null,
    val nextAlarmExact: Boolean = true,
)

interface ScheduleStore {
    var schedule: Schedule

    /** The desired state the app last acted on, or null if it has to act on the next run. */
    var lastDesiredWorkOn: Boolean?

    var status: RunStatus?
}

/** The work profile the app is allowed to act on, resolved right before each use. */
interface WorkProfileTarget {
    fun isPaused(): Boolean

    /**
     * Asks the system to pause or unpause. Unpausing never shows a credential screen; it returns
     * false instead if one would be needed. May throw SecurityException or IllegalArgumentException.
     */
    fun requestPaused(paused: Boolean): Boolean
}

sealed interface TargetLookup {
    data class Found(val target: WorkProfileTarget) : TargetLookup
    data object Missing : TargetLookup
    data object Unconfirmed : TargetLookup
}

interface QuietModeController {
    fun hasPermission(): Boolean
    fun findTarget(): TargetLookup
}

interface AlarmScheduler {
    /** Arms the single boundary alarm, replacing any earlier one. Returns true if it is exact. */
    fun armBoundary(at: Instant): Boolean
    fun cancelBoundary()

    /** Arms the one-shot alarm that retries a failed unpause after boot. */
    fun armRetry(at: Instant)
    fun cancelRetry()
}

interface Notifier {
    /** "Tap to turn on work apps": the work PIN is needed. */
    fun showNeedsCredential()
    fun cancelNeedsCredential()

    /** "Attention needed" for a failed apply. Repeated calls update one notification. */
    fun showProblem(result: ApplyResult)
    fun cancelProblem()
}
