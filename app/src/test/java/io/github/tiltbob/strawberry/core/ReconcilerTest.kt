package io.github.tiltbob.strawberry.core

import io.github.tiltbob.strawberry.schedule.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

class ReconcilerTest {
    private val zone = ZoneId.of("Europe/Berlin")

    // 2026-10-05 is a Monday.
    private val monday0900 = at("2026-10-05T09:00")
    private val monday1800 = at("2026-10-05T18:00")
    private val tuesday0900 = at("2026-10-06T09:00")

    private val store = FakeStore(Schedule(enabled = true))
    private val profile = FakeProfile(paused = true)
    private val quietMode = FakeQuietMode(profile)
    private val alarms = FakeAlarms()
    private val notifier = FakeNotifier()
    private var now = monday0900
    private val reconciler = Reconciler(store, quietMode, alarms, notifier, { now }, { zone })

    private fun at(local: String): Instant = LocalDateTime.parse(local).atZone(zone).toInstant()

    @Test
    fun boundaryAlarmUnpausesAndArmsTheNextBoundary() {
        store.lastDesiredWorkOn = false

        reconciler.run(Reason.ALARM, scheduledAt = monday0900)

        assertEquals(listOf(false), profile.requests)
        assertFalse(profile.paused)
        assertEquals(true, store.lastDesiredWorkOn)
        assertEquals(monday1800, alarms.boundary)
        assertEquals(ApplyResult.Ok, store.status?.lastApplyResult)
        assertEquals(Reason.ALARM, store.status?.lastReason)
        assertEquals(monday1800, store.status?.nextAlarmAt)
        assertTrue(store.status!!.nextAlarmExact)
    }

    @Test
    fun boundaryAlarmPauses() {
        store.lastDesiredWorkOn = true
        profile.paused = false
        now = monday1800

        reconciler.run(Reason.ALARM, scheduledAt = monday1800)

        assertEquals(listOf(true), profile.requests)
        assertEquals(false, store.lastDesiredWorkOn)
        assertEquals(tuesday0900, alarms.boundary)
    }

    @Test
    fun alarmDeliveredEarlyIsEvaluatedAtItsBoundary() {
        store.lastDesiredWorkOn = false
        now = monday0900.minusSeconds(2)

        reconciler.run(Reason.ALARM, scheduledAt = monday0900)

        assertEquals(listOf(false), profile.requests)
        assertEquals(monday1800, alarms.boundary)
    }

    @Test
    fun duplicateAlarmKeepsManualPause() {
        store.lastDesiredWorkOn = false
        reconciler.run(Reason.ALARM, scheduledAt = monday0900)
        // The user pauses work apps by hand, then the same alarm arrives again.
        profile.paused = true
        now = monday0900.plusSeconds(300)

        reconciler.run(Reason.ALARM, scheduledAt = monday0900)

        assertEquals(listOf(false), profile.requests)
        assertTrue(profile.paused)
        assertEquals(monday1800, alarms.boundary)
    }

    @Test
    fun staleAlarmKeepsManualChange() {
        // Work apps were turned on at 09:00 and the user paused them at 10:00.
        store.lastDesiredWorkOn = true
        profile.paused = true
        now = at("2026-10-05T11:00")

        reconciler.run(Reason.ALARM, scheduledAt = monday0900)

        assertTrue(profile.requests.isEmpty())
        assertEquals(monday1800, alarms.boundary)
    }

    @Test
    fun openingTheAppKeepsManualChange() {
        store.lastDesiredWorkOn = true
        profile.paused = true
        now = at("2026-10-05T11:00")

        reconciler.run(Reason.APP_OPENED)

        assertTrue(profile.requests.isEmpty())
        assertEquals(monday1800, alarms.boundary)
        assertEquals(Reason.APP_OPENED, store.status?.lastReason)
    }

    @Test
    fun bootForcesTheSchedule() {
        store.lastDesiredWorkOn = true
        profile.paused = true
        now = at("2026-10-05T11:00")

        reconciler.run(Reason.BOOT)

        assertEquals(listOf(false), profile.requests)
        assertFalse(profile.paused)
    }

    @Test
    fun savingTheScheduleForcesIt() {
        store.lastDesiredWorkOn = false
        profile.paused = false
        now = at("2026-10-05T20:00")

        reconciler.run(Reason.SCHEDULE_SAVED)

        assertEquals(listOf(true), profile.requests)
        assertTrue(profile.paused)
        assertEquals(tuesday0900, alarms.boundary)
    }

    @Test
    fun boundarySkippedByClockJumpIsApplied() {
        // Work apps were turned on at 09:00; the clock then jumps past 18:00.
        store.lastDesiredWorkOn = true
        profile.paused = false
        now = at("2026-10-05T19:30")

        reconciler.run(Reason.TIME_CHANGED)

        assertEquals(listOf(true), profile.requests)
        assertEquals(false, store.lastDesiredWorkOn)
        assertEquals(tuesday0900, alarms.boundary)
    }

    @Test
    fun zoneOverrideIsUsed() {
        store.lastDesiredWorkOn = false
        // 09:00 in Berlin is 16:00 in Tokyo: inside office hours there too, but ending at 18:00 Tokyo.
        val tokyo = ZoneId.of("Asia/Tokyo")

        reconciler.run(Reason.TIMEZONE_CHANGED, zoneOverride = tokyo)

        assertEquals(listOf(false), profile.requests)
        assertEquals(LocalDateTime.parse("2026-10-05T18:00").atZone(tokyo).toInstant(), alarms.boundary)
    }

    @Test
    fun needsCredentialNotifiesAndCountsAsDone() {
        store.lastDesiredWorkOn = false
        profile.unpauseAllowed = false

        reconciler.run(Reason.ALARM, scheduledAt = monday0900)

        assertEquals(ApplyResult.NeedsCredential, store.status?.lastApplyResult)
        assertTrue(notifier.needsCredential)
        assertEquals(true, store.lastDesiredWorkOn)
        assertNull("no retry outside boot", alarms.retry)
        assertEquals(monday1800, alarms.boundary)
    }

    @Test
    fun needsCredentialAtBootAlsoRetries() {
        profile.unpauseAllowed = false
        now = at("2026-10-05T10:00")

        reconciler.run(Reason.BOOT)

        assertTrue(notifier.needsCredential)
        assertEquals(now.plusSeconds(60), alarms.retry)
    }

    @Test
    fun retryIsForced() {
        store.lastDesiredWorkOn = true
        now = at("2026-10-05T10:01")

        reconciler.run(Reason.RETRY)

        assertEquals(listOf(false), profile.requests)
        assertFalse(notifier.needsCredential)
    }

    @Test
    fun pauseBoundaryWithdrawsTheCredentialPrompt() {
        store.lastDesiredWorkOn = true
        profile.paused = false
        notifier.needsCredential = true
        now = monday1800

        reconciler.run(Reason.ALARM, scheduledAt = monday1800)

        assertEquals(listOf(true), profile.requests)
        assertFalse(notifier.needsCredential)
    }

    @Test
    fun alreadyInDesiredStateSkipsTheCall() {
        store.lastDesiredWorkOn = false
        profile.paused = false

        reconciler.run(Reason.ALARM, scheduledAt = monday0900)

        assertTrue(profile.requests.isEmpty())
        assertEquals(ApplyResult.Already, store.status?.lastApplyResult)
        assertEquals(true, store.lastDesiredWorkOn)
        assertFalse(notifier.needsCredential)
    }

    @Test
    fun missingPermissionIsRetriedLaterAndNotified() {
        store.lastDesiredWorkOn = false
        quietMode.permission = false

        reconciler.run(Reason.ALARM, scheduledAt = monday0900)

        assertTrue(profile.requests.isEmpty())
        assertNull(store.lastDesiredWorkOn)
        assertEquals(listOf<ApplyResult>(ApplyResult.NoPermission), notifier.problems)
        assertEquals(ApplyResult.NoPermission, store.status?.lastApplyResult)
        assertEquals("next alarm is armed anyway", monday1800, alarms.boundary)

        // Once granted, the next run applies the schedule even though nothing is forced.
        quietMode.permission = true
        now = monday0900.plusSeconds(600)
        reconciler.run(Reason.APP_OPENED)

        assertEquals(listOf(false), profile.requests)
        assertEquals(true, store.lastDesiredWorkOn)
        assertTrue(notifier.problems.isEmpty())
    }

    @Test
    fun profileProblemsAreReportedButNotFromTheScreen() {
        quietMode.lookup = TargetLookup.Missing
        reconciler.run(Reason.APP_OPENED)
        assertEquals(ApplyResult.ProfileMissing, store.status?.lastApplyResult)
        assertTrue("the screen shows it already", notifier.problems.isEmpty())

        quietMode.lookup = TargetLookup.Unconfirmed
        reconciler.run(Reason.BOOT)
        assertEquals(ApplyResult.ProfileUnconfirmed, store.status?.lastApplyResult)
        assertEquals(listOf<ApplyResult>(ApplyResult.ProfileUnconfirmed), notifier.problems)
        assertNull(store.lastDesiredWorkOn)
        assertEquals(monday1800, alarms.boundary)
    }

    @Test
    fun securityExceptionWithPermissionMeansTheProfileIsGone() {
        profile.failure = SecurityException("not in profile group")

        reconciler.run(Reason.BOOT)

        assertEquals(ApplyResult.ProfileMissing, store.status?.lastApplyResult)
        assertNull(store.lastDesiredWorkOn)
    }

    @Test
    fun illegalArgumentIsAnError() {
        profile.failure = IllegalArgumentException("User 10 is not a profile")

        reconciler.run(Reason.BOOT)

        assertEquals(ApplyResult.Error("User 10 is not a profile"), store.status?.lastApplyResult)
        assertEquals(listOf<ApplyResult>(ApplyResult.Error("User 10 is not a profile")), notifier.problems)
        assertNull(store.lastDesiredWorkOn)
    }

    @Test
    fun disabledScheduleCancelsAlarmsAndChangesNothing() {
        store.schedule = Schedule(enabled = false)
        store.lastDesiredWorkOn = true
        alarms.boundary = monday1800
        alarms.retry = monday0900
        notifier.needsCredential = true

        reconciler.run(Reason.BOOT)

        assertTrue(profile.requests.isEmpty())
        assertNull(alarms.boundary)
        assertNull(alarms.retry)
        assertNull(store.lastDesiredWorkOn)
        assertNull(store.status?.nextAlarmAt)
        assertFalse(notifier.needsCredential)
    }

    @Test
    fun scheduleWithoutTransitionsHasNoAlarm() {
        store.schedule = Schedule(enabled = true, days = emptySet())
        alarms.boundary = monday1800
        profile.paused = false

        reconciler.run(Reason.SCHEDULE_SAVED)

        assertEquals(listOf(true), profile.requests)
        assertNull(alarms.boundary)
        assertNull(store.status?.nextAlarmAt)
    }

    @Test
    fun inexactAlarmIsRecorded() {
        alarms.exact = false

        reconciler.run(Reason.APP_OPENED)

        assertFalse(store.status!!.nextAlarmExact)
    }

    @Test
    fun lastApplyIsKeptWhenNothingIsApplied() {
        store.lastDesiredWorkOn = false
        reconciler.run(Reason.ALARM, scheduledAt = monday0900)
        now = monday0900.plusSeconds(3600)

        reconciler.run(Reason.APP_OPENED)

        val status = store.status!!
        assertEquals(monday0900, status.lastApplyAt)
        assertEquals(ApplyResult.Ok, status.lastApplyResult)
        assertEquals(now, status.lastRunAt)
    }

    private class FakeStore(override var schedule: Schedule) : ScheduleStore {
        override var lastDesiredWorkOn: Boolean? = null
        override var status: RunStatus? = null
    }

    private class FakeProfile(var paused: Boolean) : WorkProfileTarget {
        var unpauseAllowed = true
        var failure: RuntimeException? = null
        val requests = mutableListOf<Boolean>()

        override fun isPaused() = paused

        override fun requestPaused(paused: Boolean): Boolean {
            failure?.let { throw it }
            requests += paused
            if (!paused && !unpauseAllowed) return false
            this.paused = paused
            return true
        }
    }

    private class FakeQuietMode(profile: WorkProfileTarget) : QuietModeController {
        var permission = true
        var lookup: TargetLookup = TargetLookup.Found(profile)
        override fun hasPermission() = permission
        override fun findTarget() = lookup
    }

    private class FakeAlarms : AlarmScheduler {
        var boundary: Instant? = null
        var retry: Instant? = null
        var exact = true

        override fun armBoundary(at: Instant): Boolean {
            boundary = at
            return exact
        }

        override fun cancelBoundary() {
            boundary = null
        }

        override fun armRetry(at: Instant) {
            retry = at
        }

        override fun cancelRetry() {
            retry = null
        }
    }

    private class FakeNotifier : Notifier {
        var needsCredential = false
        val problems = mutableListOf<ApplyResult>()

        override fun showNeedsCredential() {
            needsCredential = true
        }

        override fun cancelNeedsCredential() {
            needsCredential = false
        }

        override fun showProblem(result: ApplyResult) {
            problems.clear()
            problems += result
        }

        override fun cancelProblem() {
            problems.clear()
        }
    }
}
