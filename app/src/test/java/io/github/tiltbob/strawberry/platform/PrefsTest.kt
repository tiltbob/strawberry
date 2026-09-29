package io.github.tiltbob.strawberry.platform

import io.github.tiltbob.strawberry.core.ApplyResult
import io.github.tiltbob.strawberry.core.Reason
import io.github.tiltbob.strawberry.core.RunStatus
import io.github.tiltbob.strawberry.schedule.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class PrefsTest {
    private val prefs = Prefs(RuntimeEnvironment.getApplication())

    @Test
    fun defaults() {
        assertEquals(Schedule(), prefs.schedule)
        assertNull(prefs.lastDesiredWorkOn)
        assertNull(prefs.status)
        assertNull(prefs.selectedProfileSerial)
    }

    @Test
    fun scheduleRoundTrips() {
        val schedule = Schedule(
            enabled = true,
            start = LocalTime.of(22, 15),
            end = LocalTime.of(6, 45),
            days = setOf(DayOfWeek.SUNDAY, DayOfWeek.WEDNESDAY),
        )
        prefs.schedule = schedule
        assertEquals(schedule, prefs.schedule)
    }

    @Test
    fun lastDesiredCanBeCleared() {
        prefs.lastDesiredWorkOn = false
        assertEquals(false, prefs.lastDesiredWorkOn)
        prefs.lastDesiredWorkOn = null
        assertNull(prefs.lastDesiredWorkOn)
    }

    @Test
    fun statusRoundTrips() {
        val results = listOf(
            ApplyResult.Ok, ApplyResult.Already, ApplyResult.NeedsCredential, ApplyResult.NoPermission,
            ApplyResult.ProfileMissing, ApplyResult.ProfileUnconfirmed, ApplyResult.Error("boom"),
        )
        for (result in results) {
            val status = RunStatus(
                lastRunAt = Instant.ofEpochMilli(1_000),
                lastReason = Reason.RETRY,
                lastApplyAt = Instant.ofEpochMilli(900),
                lastApplyResult = result,
                nextAlarmAt = Instant.ofEpochMilli(5_000),
                nextAlarmExact = false,
            )
            prefs.status = status
            assertEquals(status, prefs.status)
        }
        val bare = RunStatus(Instant.ofEpochMilli(1), Reason.APP_OPENED)
        prefs.status = bare
        assertEquals(bare, prefs.status)
    }

    @Test
    fun profileSelection() {
        prefs.selectProfile(7, confirmedByUser = true)
        assertEquals(7L, prefs.selectedProfileSerial)
        assertEquals(true, prefs.selectedProfileConfirmed)
        prefs.clearProfile()
        assertNull(prefs.selectedProfileSerial)
        assertEquals(false, prefs.selectedProfileConfirmed)
    }
}
