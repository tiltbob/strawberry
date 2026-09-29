package io.github.tiltbob.strawberry.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import io.github.tiltbob.strawberry.core.Reconciler
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The one background thread all work runs on: receivers, the screen and reconciles. Running
 * everything in order on one thread means no two reconciles ever overlap.
 */
object Background {
    val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "work-schedule")
    }

    fun execute(block: () -> Unit) = executor.execute {
        try {
            block()
        } catch (e: RuntimeException) {
            Log.e("Background", "Background task failed", e)
        }
    }
}

/** Runs [block] on [Background] and keeps the broadcast (and an alarm's wakelock) alive until it ends. */
fun BroadcastReceiver.runInBackground(block: () -> Unit) {
    val pending = goAsync()
    Background.execute {
        try {
            block()
        } finally {
            pending.finish()
        }
    }
}

fun createReconciler(context: Context): Reconciler {
    val app = context.applicationContext
    val prefs = Prefs(app)
    return Reconciler(
        store = prefs,
        quietMode = AndroidQuietModeController(app, WorkProfiles(app, prefs)),
        alarms = AndroidAlarmScheduler(app),
        notifier = AndroidNotifier(app),
        clock = Instant::now,
        zone = ZoneId::systemDefault,
    )
}
