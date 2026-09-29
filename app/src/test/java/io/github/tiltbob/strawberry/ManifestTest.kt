package io.github.tiltbob.strawberry

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import io.github.tiltbob.strawberry.receiver.BoundaryReceiver
import io.github.tiltbob.strawberry.receiver.SystemEventReceiver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class ManifestTest {
    private val app = RuntimeEnvironment.getApplication()
    private val packageManager = app.packageManager

    @Test
    fun appLabel() {
        assertEquals("Work Schedule", app.getString(R.string.app_name))
    }

    @Test
    fun declaresPermissions() {
        val info = packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        listOf(
            "android.permission.MODIFY_QUIET_MODE",
            "android.permission.USE_EXACT_ALARM",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        ).forEach { assertTrue(it, it in requested) }
        // Only needed on Android 12 and 12L (maxSdkVersion 32); tests run as a newer version.
        assertFalse("android.permission.SCHEDULE_EXACT_ALARM" in requested)
    }

    @Test
    fun receiversAreNotExported() {
        listOf(BoundaryReceiver::class.java, SystemEventReceiver::class.java).forEach {
            val info = packageManager.getReceiverInfo(ComponentName(app, it), 0)
            assertFalse(it.simpleName, info.exported)
        }
    }

    @Test
    fun systemEventReceiverListensForAllEvents() {
        listOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        ).forEach { action ->
            val receivers = packageManager.queryBroadcastReceivers(Intent(action).setPackage(app.packageName), 0)
            assertEquals(action, listOf(SystemEventReceiver::class.java.name), receivers.map { it.activityInfo.name })
        }
    }

    @Test
    fun activities() {
        val main = packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertTrue(main.exported)
        val unpause = packageManager.getActivityInfo(ComponentName(app, UnpauseActivity::class.java), 0)
        assertFalse(unpause.exported)
        assertTrue(unpause.flags and android.content.pm.ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS != 0)
        assertTrue(unpause.flags and android.content.pm.ActivityInfo.FLAG_NO_HISTORY != 0)
    }
}
