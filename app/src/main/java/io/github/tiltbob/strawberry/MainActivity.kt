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
import io.github.tiltbob.strawberry.platform.bootCount
import io.github.tiltbob.strawberry.platform.createReconciler
import io.github.tiltbob.strawberry.schedule.Schedule
import io.github.tiltbob.strawberry.ui.ScreenActions
import io.github.tiltbob.strawberry.ui.ScreenState
import io.github.tiltbob.strawberry.ui.WorkScheduleScreen
import io.github.tiltbob.strawberry.ui.theme.WorkScheduleTheme

class MainActivity : ComponentActivity(), ScreenActions {
    private var state by mutableStateOf<ScreenState?>(null)

    /** Schedule edits made on this screen. Main thread only. */
    private var edits = 0

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val openSettings =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { refresh() }

    /**
     * The system sends these only for managed profiles and only to receivers registered at
     * runtime, so while the screen is visible they both refresh it and identify the work profile.
     * They do not apply the schedule: the user may be in the middle of toggling "Work apps" to
     * identify the profile, and the next reconcile applies it once they are done.
     */
    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val user = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_USER, UserHandle::class.java)
            val app = context.applicationContext
            val editsAtStart = edits
            Background.execute {
                if (user != null) WorkProfiles(app).learnManaged(user)
                publish(ScreenState.load(app), editsAtStart)
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
        val editsAtStart = edits
        Background.execute {
            // The first launch after install never sends BOOT_COMPLETED, so storing the boot
            // count here lets a later BOOT_COMPLETED without a reboot be recognized.
            Prefs(app).rememberBootCountIfUnknown(bootCount(app))
            if (reason != null) createReconciler(app).run(reason)
            publish(ScreenState.load(app), editsAtStart)
        }
    }

    /** Call on the background thread with the value [edits] had when the task was queued. */
    private fun publish(newState: ScreenState, editsAtStart: Int) {
        val profile = (newState.profile as? ProfileState.Selected)?.profile
        if (profile != null && !profile.paused) AndroidNotifier(applicationContext).cancelNeedsCredential()
        runOnUiThread {
            // Tasks run in order on Background, so a load queued after the latest edit already sees
            // it saved. Only a load queued before a newer edit keeps the screen's copy, so that it
            // does not undo that edit. Otherwise the saved schedule wins, which also picks up
            // edits made in another copy of this screen.
            val shown = state
            state = if (shown != null && editsAtStart != edits) {
                newState.copy(schedule = shown.schedule)
            } else {
                newState
            }
        }
    }

    override fun onScheduleChange(schedule: Schedule) {
        edits++
        val editsAtStart = edits
        state = state?.copy(schedule = schedule)
        val app = applicationContext
        Background.execute {
            // Confirming a time without changing it is not an edit. Saving it would force the
            // schedule and undo a manual pause or unpause.
            val prefs = Prefs(app)
            if (prefs.schedule != schedule) {
                prefs.schedule = schedule
                createReconciler(app).run(Reason.SCHEDULE_SAVED)
            }
            publish(ScreenState.load(app), editsAtStart)
        }
    }

    override fun onChooseProfile(serial: Long) {
        val app = applicationContext
        val editsAtStart = edits
        Background.execute {
            WorkProfiles(app).choose(serial)
            // Choosing another profile forgets the state applied to the previous one, and nothing
            // was applied while the profile was unknown, so this run applies the schedule.
            createReconciler(app).run(Reason.APP_OPENED)
            publish(ScreenState.load(app), editsAtStart)
        }
    }

    override fun onRescan() = refresh(Reason.APP_OPENED)

    override fun onRefresh() = refresh()

    override fun onCopy(text: String) {
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
    }

    override fun onAllowNotifications() {
        val prefs = Prefs(this)
        // After the user declined for good, the system no longer asks and only the settings
        // screen can allow notifications.
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
        val canAsk = permission != null &&
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED &&
            (!prefs.askedForNotifications || shouldShowRequestPermissionRationale(permission))
        if (permission != null && canAsk) {
            prefs.askedForNotifications = true
            requestNotifications.launch(permission)
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
