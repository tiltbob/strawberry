package io.github.tiltbob.strawberry.ui

import android.content.Context
import android.os.PowerManager
import android.util.Log
import androidx.core.content.PackageManagerCompat
import androidx.core.content.UnusedAppRestrictionsConstants
import io.github.tiltbob.strawberry.core.RunStatus
import io.github.tiltbob.strawberry.platform.Prefs
import io.github.tiltbob.strawberry.platform.ProfileState
import io.github.tiltbob.strawberry.platform.WorkProfiles
import io.github.tiltbob.strawberry.platform.canPostNotifications
import io.github.tiltbob.strawberry.platform.canScheduleExactAlarms
import io.github.tiltbob.strawberry.platform.hasQuietModePermission
import io.github.tiltbob.strawberry.schedule.Schedule
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/** Everything the screen shows, read in one go on the background thread. */
data class ScreenState(
    val schedule: Schedule,
    val profile: ProfileState,
    val status: RunStatus?,
    val hasQuietModePermission: Boolean,
    val userId: Int,
    val notificationsAllowed: Boolean,
    val exactAlarmsAllowed: Boolean,
    val unusedAppRestrictionsOn: Boolean,
    val batteryUnrestricted: Boolean,
    val now: Instant,
    val zone: ZoneId,
) {
    /** Number of unfinished required setup steps (the battery step is only recommended). */
    val requiredStepsLeft: Int
        get() = listOf(
            !hasQuietModePermission,
            profile !is ProfileState.Selected,
            !notificationsAllowed,
            !exactAlarmsAllowed,
            unusedAppRestrictionsOn,
        ).count { it }

    companion object {
        fun load(context: Context): ScreenState {
            val prefs = Prefs(context)
            return ScreenState(
                schedule = prefs.schedule,
                profile = WorkProfiles(context, prefs).resolve(),
                status = prefs.status,
                hasQuietModePermission = hasQuietModePermission(context),
                userId = WorkProfiles.myUserId(),
                notificationsAllowed = canPostNotifications(context),
                exactAlarmsAllowed = canScheduleExactAlarms(context),
                unusedAppRestrictionsOn = unusedAppRestrictionsOn(context),
                batteryUnrestricted = context.getSystemService(PowerManager::class.java)
                    .isIgnoringBatteryOptimizations(context.packageName),
                now = Instant.now(),
                zone = ZoneId.systemDefault(),
            )
        }

        /** Whether Android may hibernate the app (and drop its alarms) when it is not opened. */
        private fun unusedAppRestrictionsOn(context: Context): Boolean = try {
            val status = PackageManagerCompat.getUnusedAppRestrictionsStatus(context).get(2, TimeUnit.SECONDS)
            status == UnusedAppRestrictionsConstants.API_30_BACKPORT ||
                status == UnusedAppRestrictionsConstants.API_30 ||
                status == UnusedAppRestrictionsConstants.API_31
        } catch (e: Exception) {
            Log.w("ScreenState", "Could not read unused app restrictions", e)
            false
        }
    }
}
