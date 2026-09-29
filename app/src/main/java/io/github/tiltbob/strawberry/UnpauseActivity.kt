package io.github.tiltbob.strawberry

import android.app.Activity
import android.os.Bundle
import android.os.UserManager
import android.util.Log
import android.widget.Toast
import io.github.tiltbob.strawberry.platform.AndroidAlarmScheduler
import io.github.tiltbob.strawberry.platform.AndroidNotifier
import io.github.tiltbob.strawberry.platform.ProfileState
import io.github.tiltbob.strawberry.platform.WorkProfiles
import io.github.tiltbob.strawberry.platform.hasQuietModePermission

/**
 * Opened from the "Tap to turn on work apps" notification. Because the app is in the foreground
 * now, it can let the system ask for the work PIN. Shows no UI of its own.
 */
class UnpauseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidNotifier(this).cancelNeedsCredential()
        // The user is handling it now, so the boot retry must not post the prompt again.
        AndroidAlarmScheduler(this).cancelRetry()
        unpause()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        finish()
    }

    /** Returns a message for the user if something went wrong. */
    private fun unpause(): Int? {
        if (!hasQuietModePermission(this)) return R.string.result_no_permission
        val handle = when (val state = WorkProfiles(this).resolve()) {
            is ProfileState.Selected -> state.profile.handle
            ProfileState.None -> return R.string.result_profile_missing
            else -> return R.string.result_profile_unconfirmed
        }
        val userManager = getSystemService(UserManager::class.java)
        return try {
            if (userManager.isQuietModeEnabled(handle)) {
                // Without flags the system shows its own PIN screen if one is needed.
                userManager.requestQuietModeEnabled(false, handle)
            }
            null
        } catch (e: RuntimeException) {
            // SecurityException or IllegalArgumentException: the profile is gone or not usable.
            Log.w("UnpauseActivity", "Could not turn on work apps", e)
            R.string.unpause_failed
        }
    }
}
