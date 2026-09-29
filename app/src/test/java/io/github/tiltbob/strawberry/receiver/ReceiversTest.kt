package io.github.tiltbob.strawberry.receiver

import android.content.Intent
import android.provider.Settings
import io.github.tiltbob.strawberry.MainActivity
import io.github.tiltbob.strawberry.core.Reason
import io.github.tiltbob.strawberry.idleEverything
import io.github.tiltbob.strawberry.platform.Prefs
import io.github.tiltbob.strawberry.schedule.Schedule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowAlarmManager
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
class ReceiversTest {
    private val app = RuntimeEnvironment.getApplication()
    private val prefs = Prefs(app)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        prefs.schedule = Schedule(enabled = true)
    }

    @After
    fun tearDown() = ShadowAlarmManager.setCanScheduleExactAlarms(false)

    private fun lastReason(): Reason? = prefs.status?.lastReason

    @Test
    fun systemEventsRouteToTheReconciler() {
        val events = mapOf(
            Intent.ACTION_BOOT_COMPLETED to Reason.BOOT,
            Intent.ACTION_MY_PACKAGE_REPLACED to Reason.PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED to Reason.TIME_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" to
                Reason.EXACT_ALARM_PERMISSION_CHANGED,
        )
        for ((action, reason) in events) {
            app.sendBroadcast(Intent(action))
            idleEverything()
            assertEquals(action, reason, lastReason())
        }
        assertNotNull("an alarm is armed", prefs.status?.nextAlarmAt)
    }

    @Test
    fun bootCompletedWithoutARebootIsNotForced() {
        fun boot(count: Int): Reason? {
            Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, count)
            app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
            idleEverything()
            return lastReason()
        }

        assertEquals(Reason.BOOT, boot(3))
        // Android 15+ sends it again after a force stop; the boot count stays the same.
        assertEquals(Reason.RESTARTED, boot(3))
        assertEquals(Reason.BOOT, boot(4))
    }

    @Test
    fun bootCompletedAfterAForceStopIsNotForcedBeforeTheFirstReboot() {
        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 3)
        // Opening the app after install stores the boot count; that launch sends no BOOT_COMPLETED.
        Robolectric.buildActivity(MainActivity::class.java).use { it.setup() }
        idleEverything()

        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
        idleEverything()
        assertEquals(Reason.RESTARTED, lastReason())

        Settings.Global.putInt(app.contentResolver, Settings.Global.BOOT_COUNT, 4)
        app.sendBroadcast(Intent(Intent.ACTION_BOOT_COMPLETED))
        idleEverything()
        assertEquals(Reason.BOOT, lastReason())
    }

    @Test
    fun timezoneChangeUsesTheZoneFromTheBroadcast() {
        val zone = ZoneId.of("Pacific/Kiritimati")
        val before = Instant.now()

        app.sendBroadcast(Intent(Intent.ACTION_TIMEZONE_CHANGED).putExtra(Intent.EXTRA_TIMEZONE, zone.id))
        idleEverything()

        val status = prefs.status!!
        assertEquals(Reason.TIMEZONE_CHANGED, status.lastReason)
        assertEquals(prefs.schedule.nextTransition(before, zone), status.nextAlarmAt)
    }

    @Test
    fun boundaryAlarmRoutesToTheReconciler() {
        app.sendBroadcast(
            Intent(app, BoundaryReceiver::class.java)
                .setAction(BoundaryReceiver.ACTION_BOUNDARY)
                .putExtra(BoundaryReceiver.EXTRA_SCHEDULED_AT, Instant.now().toEpochMilli()),
        )
        idleEverything()
        assertEquals(Reason.ALARM, lastReason())

        app.sendBroadcast(Intent(app, BoundaryReceiver::class.java).setAction(BoundaryReceiver.ACTION_RETRY))
        idleEverything()
        assertEquals(Reason.RETRY, lastReason())
    }

    @Test
    fun unknownActionsAreIgnored() {
        app.sendBroadcast(Intent(app, BoundaryReceiver::class.java).setAction("something.else"))
        idleEverything()
        assertEquals(null, lastReason())
    }
}
