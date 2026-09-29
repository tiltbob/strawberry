package io.github.tiltbob.strawberry.platform

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import io.github.tiltbob.strawberry.core.AlarmScheduler
import io.github.tiltbob.strawberry.receiver.BoundaryReceiver
import java.time.Instant

/**
 * One exact, allowed-while-idle alarm for the next schedule boundary, plus a separate one-shot
 * alarm for the boot retry. Both are explicit broadcasts to [BoundaryReceiver].
 */
class AndroidAlarmScheduler(context: Context) : AlarmScheduler {
    private val app = context.applicationContext
    private val alarmManager = app.getSystemService(AlarmManager::class.java)

    override fun armBoundary(at: Instant): Boolean =
        set(at, pendingIntent(BoundaryReceiver.ACTION_BOUNDARY, REQUEST_BOUNDARY, at))

    override fun cancelBoundary() = cancel(BoundaryReceiver.ACTION_BOUNDARY, REQUEST_BOUNDARY)

    override fun armRetry(at: Instant) {
        set(at, pendingIntent(BoundaryReceiver.ACTION_RETRY, REQUEST_RETRY, at))
    }

    override fun cancelRetry() = cancel(BoundaryReceiver.ACTION_RETRY, REQUEST_RETRY)

    /** Returns true if the alarm is exact. */
    private fun set(at: Instant, operation: PendingIntent): Boolean {
        val millis = at.toEpochMilli()
        if (canScheduleExactAlarms(app)) {
            try {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
                return true
            } catch (e: SecurityException) {
                Log.w(TAG, "Exact alarm refused, falling back to an inexact one", e)
            }
        }
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operation)
        } catch (e: SecurityException) {
            Log.e(TAG, "Could not set an alarm", e)
        }
        return false
    }

    private fun cancel(action: String, requestCode: Int) {
        val existing = PendingIntent.getBroadcast(
            app,
            requestCode,
            intent(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) ?: return
        alarmManager.cancel(existing)
        existing.cancel()
    }

    private fun pendingIntent(action: String, requestCode: Int, at: Instant): PendingIntent =
        PendingIntent.getBroadcast(
            app,
            requestCode,
            intent(action).putExtra(BoundaryReceiver.EXTRA_SCHEDULED_AT, at.toEpochMilli()),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun intent(action: String) = Intent(app, BoundaryReceiver::class.java)
        .setAction(action)
        // The foreground broadcast queue delivers promptly, like the system's own schedules.
        .addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

    private companion object {
        const val TAG = "AlarmScheduler"
        const val REQUEST_BOUNDARY = 1
        const val REQUEST_RETRY = 2
    }
}

/**
 * Android 11 has no exact-alarm permission and 13+ grants USE_EXACT_ALARM at install, so in
 * practice this is only false on Android 12 and 12L when the user revoked "Alarms & reminders".
 */
fun canScheduleExactAlarms(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
