package io.github.tiltbob.strawberry.core

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * The single place where the schedule is applied. Every entry point (alarms, system events, the
 * app's screen) calls [run]; it is idempotent and must be called on the shared background thread.
 *
 * The app only acts at schedule boundaries: it remembers the desired state it last acted on and
 * applies the schedule again only when that desired state changes, or when the run is forced
 * (after boot, after the schedule was edited, and for the boot retry). A manual pause or unpause
 * in between is left alone until the next boundary.
 */
class Reconciler(
    private val store: ScheduleStore,
    private val quietMode: QuietModeController,
    private val alarms: AlarmScheduler,
    private val notifier: Notifier,
    private val clock: () -> Instant,
    private val zone: () -> ZoneId,
) {
    fun run(reason: Reason, scheduledAt: Instant? = null, zoneOverride: ZoneId? = null) {
        val now = clock()
        val schedule = store.schedule
        var status = (store.status ?: RunStatus(now, reason)).copy(lastRunAt = now, lastReason = reason)
        if (!schedule.enabled) {
            alarms.cancelBoundary()
            alarms.cancelRetry()
            notifier.cancelNeedsCredential()
            notifier.cancelProblem()
            store.lastDesiredWorkOn = null
            store.status = status.copy(nextAlarmAt = null)
            return
        }

        val zone = zoneOverride ?: zone()
        // Never evaluate an alarm before its boundary, even if it is delivered a little early.
        val evalAt = if (scheduledAt != null && scheduledAt > now) scheduledAt else now
        val desiredWorkOn = schedule.isWorkOnAt(evalAt, zone)

        if (reason.forcesApply || store.lastDesiredWorkOn != desiredWorkOn) {
            val result = apply(desiredWorkOn)
            handleResult(result, desiredWorkOn, reason, now)
            status = status.copy(lastApplyAt = now, lastApplyResult = result)
        }

        // Always arm the next boundary, even if applying failed.
        val next = schedule.nextTransition(evalAt, zone)
        val exact = if (next == null) {
            alarms.cancelBoundary()
            true
        } else {
            alarms.armBoundary(next)
        }
        store.status = status.copy(nextAlarmAt = next, nextAlarmExact = exact)
    }

    private fun apply(workOn: Boolean): ApplyResult {
        if (!quietMode.hasPermission()) return ApplyResult.NoPermission
        val target = when (val lookup = quietMode.findTarget()) {
            is TargetLookup.Found -> lookup.target
            TargetLookup.Missing -> return ApplyResult.ProfileMissing
            TargetLookup.Unconfirmed -> return ApplyResult.ProfileUnconfirmed
        }
        return try {
            // On Android 14+ even a no-op unpause runs the credential check, so skip it.
            when {
                target.isPaused() == !workOn -> ApplyResult.Already
                target.requestPaused(!workOn) -> ApplyResult.Ok
                workOn -> ApplyResult.NeedsCredential
                else -> ApplyResult.Error("The system refused to pause work apps")
            }
        } catch (e: SecurityException) {
            // With the permission granted this means the profile left our profile group.
            if (quietMode.hasPermission()) ApplyResult.ProfileMissing else ApplyResult.NoPermission
        } catch (e: IllegalArgumentException) {
            ApplyResult.Error(e.message ?: "The system rejected the work profile")
        }
    }

    private fun handleResult(result: ApplyResult, workOn: Boolean, reason: Reason, now: Instant) {
        if (result.isDefinitive) {
            store.lastDesiredWorkOn = workOn
            notifier.cancelProblem()
        } else {
            // Leave the desired state unset so the next run tries again.
            store.lastDesiredWorkOn = null
            if (!reason.isFromUi) notifier.showProblem(result)
        }

        if (result == ApplyResult.NeedsCredential) {
            notifier.showNeedsCredential()
            // Right after boot an unpause can fail for a moment; try once more shortly.
            if (reason == Reason.BOOT) alarms.armRetry(now + BOOT_RETRY_DELAY)
        } else if (result.isDefinitive || !workOn) {
            // Work apps are on now, or the schedule wants them paused: the prompt is stale.
            notifier.cancelNeedsCredential()
        }
    }

    companion object {
        val BOOT_RETRY_DELAY: Duration = Duration.ofSeconds(60)
    }
}
