package io.github.tiltbob.strawberry.platform

import android.app.AlarmManager
import android.content.ComponentName
import android.content.Intent
import io.github.tiltbob.strawberry.receiver.BoundaryReceiver
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class AndroidAlarmSchedulerTest {
    private val app = RuntimeEnvironment.getApplication()
    private val alarmManager = shadowOf(app.getSystemService(AlarmManager::class.java))
    private val scheduler = AndroidAlarmScheduler(app)
    private val at = Instant.parse("2030-01-07T08:00:00Z")

    @After
    fun tearDown() = ShadowAlarmManager.setCanScheduleExactAlarms(false)

    @Test
    fun armsOneExactAlarmForTheBoundary() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)

        assertTrue(scheduler.armBoundary(at.minusSeconds(3600)))
        assertTrue(scheduler.armBoundary(at))

        val alarm = alarmManager.scheduledAlarms.single()
        assertEquals(AlarmManager.RTC_WAKEUP, alarm.type)
        assertEquals(at.toEpochMilli(), alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)
        assertEquals(ShadowAlarmManager.WINDOW_EXACT, alarm.windowLengthMs)

        val operation = shadowOf(alarm.operation)
        assertTrue(operation.isBroadcast)
        assertTrue(operation.isImmutable)
        val intent = operation.savedIntent
        assertEquals(ComponentName(app, BoundaryReceiver::class.java), intent.component)
        assertEquals(BoundaryReceiver.ACTION_BOUNDARY, intent.action)
        assertEquals(at.toEpochMilli(), intent.getLongExtra(BoundaryReceiver.EXTRA_SCHEDULED_AT, -1))
        assertTrue(intent.flags and Intent.FLAG_RECEIVER_FOREGROUND != 0)
    }

    @Test
    fun fallsBackToAnInexactAlarmWithoutPermission() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)

        assertFalse(scheduler.armBoundary(at))

        val alarm = alarmManager.scheduledAlarms.single()
        assertEquals(at.toEpochMilli(), alarm.triggerAtMs)
        assertTrue(alarm.isAllowWhileIdle)
    }

    @Test
    fun retryIsSeparateFromTheBoundary() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        scheduler.armBoundary(at)
        scheduler.armRetry(at.minusSeconds(600))
        assertEquals(2, alarmManager.scheduledAlarms.size)

        scheduler.cancelRetry()
        val remaining = alarmManager.scheduledAlarms.single()
        assertEquals(BoundaryReceiver.ACTION_BOUNDARY, shadowOf(remaining.operation).savedIntent.action)

        scheduler.cancelBoundary()
        assertTrue(alarmManager.scheduledAlarms.isEmpty())
    }
}
