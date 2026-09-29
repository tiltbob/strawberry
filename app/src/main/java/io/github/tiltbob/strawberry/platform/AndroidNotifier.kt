package io.github.tiltbob.strawberry.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.tiltbob.strawberry.MainActivity
import io.github.tiltbob.strawberry.R
import io.github.tiltbob.strawberry.UnpauseActivity
import io.github.tiltbob.strawberry.core.ApplyResult
import io.github.tiltbob.strawberry.core.Notifier

class AndroidNotifier(context: Context) : Notifier {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)

    override fun showNeedsCredential() = post(
        id = ID_NEEDS_CREDENTIAL,
        title = app.getString(R.string.notification_unpause_title),
        text = app.getString(R.string.notification_unpause_text),
        intent = Intent(app, UnpauseActivity::class.java),
    )

    override fun cancelNeedsCredential() = manager.cancel(ID_NEEDS_CREDENTIAL)

    override fun showProblem(result: ApplyResult) = post(
        id = ID_PROBLEM,
        title = app.getString(R.string.notification_problem_title),
        text = describeResult(app, result),
        // The same intent as the launcher's, so a tap brings back the open screen instead of
        // stacking a second copy of it.
        intent = Intent.makeMainActivity(ComponentName(app, MainActivity::class.java)),
    )

    override fun cancelProblem() = manager.cancel(ID_PROBLEM)

    private fun post(id: Int, title: String, text: String, intent: Intent) {
        if (!canPostNotifications(app)) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS,
                app.getString(R.string.notification_channel_alerts),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        val tap = PendingIntent.getActivity(
            app,
            id,
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(app, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            manager.notify(id, notification)
        } catch (e: SecurityException) {
            Log.w("Notifier", "Notification permission revoked", e)
        }
    }

    companion object {
        private const val CHANNEL_ALERTS = "alerts"
        private const val ID_NEEDS_CREDENTIAL = 1
        private const val ID_PROBLEM = 2
    }
}

fun canPostNotifications(context: Context): Boolean {
    val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    return granted && context.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
}

/** A plain-language sentence for [result]. */
fun describeResult(context: Context, result: ApplyResult): String = when (result) {
    ApplyResult.Ok -> context.getString(R.string.result_ok)
    ApplyResult.Already -> context.getString(R.string.result_already)
    ApplyResult.NeedsCredential -> context.getString(R.string.result_needs_credential)
    ApplyResult.NoPermission -> context.getString(R.string.result_no_permission)
    ApplyResult.ProfileMissing -> context.getString(R.string.result_profile_missing)
    ApplyResult.ProfileUnconfirmed -> context.getString(R.string.result_profile_unconfirmed)
    ApplyResult.PauseRefused -> context.getString(R.string.result_pause_refused)
    is ApplyResult.Error -> if (result.message.isBlank()) {
        context.getString(R.string.result_error_unknown)
    } else {
        context.getString(R.string.result_error, result.message)
    }
}
