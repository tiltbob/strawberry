package io.github.tiltbob.strawberry

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.net.toUri
import io.github.tiltbob.strawberry.core.Reason
import io.github.tiltbob.strawberry.platform.AndroidNotifier
import io.github.tiltbob.strawberry.platform.Background
import io.github.tiltbob.strawberry.platform.Prefs
import io.github.tiltbob.strawberry.platform.ProfileState
import io.github.tiltbob.strawberry.platform.WorkProfiles
import io.github.tiltbob.strawberry.platform.createReconciler
import io.github.tiltbob.strawberry.schedule.Schedule
import io.github.tiltbob.strawberry.ui.ScreenActions
import io.github.tiltbob.strawberry.ui.ScreenState
import io.github.tiltbob.strawberry.ui.WorkScheduleScreen
import io.github.tiltbob.strawberry.ui.theme.WorkScheduleTheme

class MainActivity : ComponentActivity(), ScreenActions {
    private var state by mutableStateOf<ScreenState?>(null)

    /** The system asks only once or twice; after that only the settings screen can allow it. */
    private var requestedNotifications = false

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val openSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    /**
     * The system sends these only for managed profiles and only to receivers registered at
     * runtime, so while the screen is visible they both refresh it and identify the work profile.
     */
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val user = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_USER, UserHandle::class.java)
            val app = context.applicationContext
            Background.execute {
                if (user != null) WorkProfiles(app).learnManaged(user)
                createReconciler(app).run(Reason.APP_OPENED)
                publish(ScreenState.load(app))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WorkScheduleTheme {
                WorkScheduleScreen(state = state, actions = this)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
        }
        ContextCompat.registerReceiver(this, profileReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        // Also re-arms the alarm, which repairs a force-stop.
        refresh(Reason.APP_OPENED)
    }

    override fun onStop() {
        unregisterReceiver(profileReceiver)
        super.onStop()
    }

    private fun refresh(reason: Reason? = null) {
        val app = applicationContext
        Background.execute {
            if (reason != null) createReconciler(app).run(reason)
            publish(ScreenState.load(app))
        }
    }

    /** Call on the background thread. */
    private fun publish(newState: ScreenState) {
        val profile = (newState.profile as? ProfileState.Selected)?.profile
        if (profile != null && !profile.paused) AndroidNotifier(applicationContext).cancelNeedsCredential()
        runOnUiThread { state = newState }
    }

    override fun onScheduleChange(schedule: Schedule) {
        state = state?.copy(schedule = schedule)
        val app = applicationContext
        Background.execute {
            Prefs(app).schedule = schedule
            createReconciler(app).run(Reason.SCHEDULE_SAVED)
            publish(ScreenState.load(app))
        }
    }

    override fun onChooseProfile(serial: Long) {
        val app = applicationContext
        Background.execute {
            WorkProfiles(app).choose(serial)
            // Nothing was applied while the profile was unknown, so this run applies the schedule.
            createReconciler(app).run(Reason.APP_OPENED)
            publish(ScreenState.load(app))
        }
    }

    override fun onRescan() = refresh(Reason.APP_OPENED)

    override fun onCopy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    override fun onAllowNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED &&
            !requestedNotifications
        ) {
            requestedNotifications = true
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            open(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName),
            )
        }
    }

    override fun onOpenExactAlarmSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, packageUri()))
        }
    }

    override fun onOpenUnusedAppSettings() =
        open(IntentCompat.createManageUnusedAppRestrictionsIntent(this, packageName))

    // The app is sideloaded and its whole job is to run at exact times, so asking is appropriate.
    @SuppressLint("BatteryLife")
    override fun onRequestBatteryExemption() =
        open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri()))

    override fun onTurnOnWorkApps() {
        startActivity(Intent(this, UnpauseActivity::class.java))
    }

    private fun packageUri() = "package:$packageName".toUri()

    private fun open(intent: Intent) {
        try {
            openSettings.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w("MainActivity", "No settings screen for $intent", e)
        }
    }
}
