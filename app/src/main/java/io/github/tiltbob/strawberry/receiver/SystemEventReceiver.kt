package io.github.tiltbob.strawberry.receiver

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tiltbob.strawberry.core.Reason
import io.github.tiltbob.strawberry.platform.Prefs
import io.github.tiltbob.strawberry.platform.bootCount
import io.github.tiltbob.strawberry.platform.createReconciler
import io.github.tiltbob.strawberry.platform.runInBackground
import java.time.DateTimeException
import java.time.ZoneId

/** Re-arms (and after boot, re-applies) the schedule after events that lose or move alarms. */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> Reason.BOOT
            Intent.ACTION_MY_PACKAGE_REPLACED -> Reason.PACKAGE_REPLACED
            Intent.ACTION_TIME_CHANGED -> Reason.TIME_CHANGED
            Intent.ACTION_TIMEZONE_CHANGED -> Reason.TIMEZONE_CHANGED
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED ->
                Reason.EXACT_ALARM_PERMISSION_CHANGED
            else -> return
        }
        // This process' default zone may not have caught up yet; the broadcast carries the new one.
        val zone = if (reason == Reason.TIMEZONE_CHANGED) zoneFrom(intent) else null
        val app = context.applicationContext
        runInBackground {
            val actual = if (reason == Reason.BOOT && !isNewBoot(app)) Reason.RESTARTED else reason
            createReconciler(app).run(actual, zoneOverride = zone)
        }
    }

    /**
     * BOOT_COMPLETED also arrives without a reboot (see [Reason.RESTARTED]). Only a reboot (or a
     * restart of the system itself, which also loses alarms) changes the boot count. The count is
     * first stored when the app is opened, which never sends BOOT_COMPLETED. If the count cannot
     * be read, assume a reboot.
     */
    private fun isNewBoot(context: Context): Boolean {
        val count = bootCount(context)
        val prefs = Prefs(context)
        if (count != -1 && count == prefs.lastBootCount) return false
        prefs.lastBootCount = count
        return true
    }

    private fun zoneFrom(intent: Intent): ZoneId? = try {
        intent.getStringExtra(Intent.EXTRA_TIMEZONE)?.let(ZoneId::of)
    } catch (e: DateTimeException) {
        null
    }
}
