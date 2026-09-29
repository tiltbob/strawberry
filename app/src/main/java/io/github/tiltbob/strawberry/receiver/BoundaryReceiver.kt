package io.github.tiltbob.strawberry.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.tiltbob.strawberry.core.Reason
import io.github.tiltbob.strawberry.platform.createReconciler
import io.github.tiltbob.strawberry.platform.runInBackground
import java.time.Instant

/** Target of the app's own alarms. Not exported and has no intent filter. */
class BoundaryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reason = when (intent.action) {
            ACTION_BOUNDARY -> Reason.ALARM
            ACTION_RETRY -> Reason.RETRY
            else -> return
        }
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, -1)
            .takeIf { reason == Reason.ALARM && it >= 0 }
            ?.let(Instant::ofEpochMilli)
        val app = context.applicationContext
        runInBackground { createReconciler(app).run(reason, scheduledAt) }
    }

    companion object {
        const val ACTION_BOUNDARY = "io.github.tiltbob.strawberry.action.BOUNDARY"
        const val ACTION_RETRY = "io.github.tiltbob.strawberry.action.RETRY"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
    }
}
