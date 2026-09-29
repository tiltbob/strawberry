package io.github.tiltbob.strawberry.platform

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Parcel
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.tiltbob.strawberry.R
import io.github.tiltbob.strawberry.core.QuietModeController
import io.github.tiltbob.strawberry.core.TargetLookup
import io.github.tiltbob.strawberry.core.WorkProfileTarget

/** What the app knows about another profile of this user. */
enum class ProfileKind {
    /** Positively identified as a work (managed) profile. */
    MANAGED,

    /** Positively identified as some other kind of profile, such as a clone profile. Never used. */
    NOT_MANAGED,

    /** Hidden from apps, such as the private space. Never used. */
    HIDDEN,

    /** Could not be identified. Used only if the user explicitly picked it. */
    UNKNOWN,
}

data class ProfileCandidate(
    val handle: UserHandle,
    /** Never reused by the system, so this is what the app remembers. */
    val serial: Long,
    val kind: ProfileKind,
    val paused: Boolean,
    /** For example "Work profile". */
    val label: String,
)

/** Which work profile the app acts on, or why it has none. */
sealed interface ProfileState {
    /** [alternatives] are other profiles the user could switch to. */
    data class Selected(
        val profile: ProfileCandidate,
        val alternatives: List<ProfileCandidate>,
    ) : ProfileState

    /**
     * The user picks one: there is more than one work profile (for example Samsung Secure
     * Folder), or [chosenIsGone] because the profile chosen earlier is gone or cannot be verified.
     */
    data class NeedsChoice(
        val candidates: List<ProfileCandidate>,
        val chosenIsGone: Boolean = false,
    ) : ProfileState

    /** Profiles exist but none could be identified: the user has to help. */
    data class NeedsConfirmation(val candidates: List<ProfileCandidate>) : ProfileState

    /** No work profile at all. */
    data object None : ProfileState
}

/**
 * Finds the work profile among the user's profiles. On Android 14 QPR2 and later the system
 * pauses any kind of profile it is given (clone profiles, the private space), so the app only
 * acts on a profile that is positively identified as managed, or that the user explicitly chose.
 */
class WorkProfiles(context: Context, private val prefs: Prefs = Prefs(context)) {
    private val app = context.applicationContext
    private val userManager = app.getSystemService(UserManager::class.java)

    /** All other profiles in this user's profile group, classified. */
    fun candidates(): List<ProfileCandidate> {
        val me = Process.myUserHandle()
        val others = userManager.userProfiles.filter { it != me }
        if (others.isEmpty()) return emptyList()
        // Hidden API, so only read when LauncherApps cannot tell (always on Android 11 to 14).
        val managedByUserInfo by lazy { readManagedFlags() }
        val learned = prefs.learnedManagedSerials
        val baseLabel = app.getString(R.string.profile_badge_base)
        return others.mapNotNull { handle ->
            try {
                val serial = userManager.getSerialNumberForUser(handle)
                val kind = classify(handle, { managedByUserInfo }, serial in learned)
                ProfileCandidate(
                    handle = handle,
                    serial = serial,
                    kind = kind,
                    paused = userManager.isQuietModeEnabled(handle),
                    label = labelFor(handle, serial, baseLabel),
                )
            } catch (e: RuntimeException) {
                // One odd profile must not break the others.
                Log.w(TAG, "Could not inspect profile $handle", e)
                null
            }
        }
    }

    /**
     * Resolves the profile to act on, right now. Selects the work profile automatically only when
     * no profile has been chosen yet and exactly one is positively identified. A remembered choice
     * is never replaced here: if that profile is gone, the user has to choose again.
     */
    fun resolve(): ProfileState {
        val all = candidates()
        val usable = all.filter { it.kind == ProfileKind.MANAGED || it.kind == ProfileKind.UNKNOWN }
        val stored = prefs.selectedProfileSerial
        val selected = usable.firstOrNull { it.serial == stored }?.takeIf {
            it.kind == ProfileKind.MANAGED || prefs.selectedProfileConfirmed
        }
        if (selected != null) return ProfileState.Selected(selected, usable - selected)

        val managed = usable.filter { it.kind == ProfileKind.MANAGED }
        return when {
            stored == null && managed.size == 1 -> {
                prefs.selectProfile(managed.single().serial, confirmedByUser = false)
                ProfileState.Selected(managed.single(), usable - managed.single())
            }
            stored != null && managed.isNotEmpty() -> ProfileState.NeedsChoice(managed, chosenIsGone = true)
            managed.size > 1 -> ProfileState.NeedsChoice(managed)
            usable.isNotEmpty() -> ProfileState.NeedsConfirmation(usable)
            else -> ProfileState.None
        }
    }

    /**
     * The system's name for the profile, such as "Work profile": the word "profile" badged the way
     * the system badges app labels for that profile.
     */
    private fun labelFor(handle: UserHandle, serial: Long, base: String): String {
        val badged = app.packageManager.getUserBadgedLabel(base, handle).toString()
        return if (badged == base) {
            app.getString(R.string.profile_unnamed, serial)
        } else {
            badged.replaceFirstChar { it.titlecase() }
        }
    }

    /** The user picked [serial] from a list. */
    fun choose(serial: Long) = prefs.selectProfile(serial, confirmedByUser = true)

    /** The system announced that [handle] is a managed profile. */
    fun learnManaged(handle: UserHandle) {
        try {
            prefs.learnManagedSerial(userManager.getSerialNumberForUser(handle))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not remember profile $handle", e)
        }
    }

    private fun classify(
        handle: UserHandle,
        managedByUserInfo: () -> Map<UserHandle, Boolean>?,
        learnedManaged: Boolean,
    ): ProfileKind {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            classifyWithLauncherApps(handle)?.let { return it }
        }
        managedByUserInfo()?.get(handle)?.let { managed ->
            return if (managed) ProfileKind.MANAGED else ProfileKind.NOT_MANAGED
        }
        return if (learnedManaged) ProfileKind.MANAGED else ProfileKind.UNKNOWN
    }

    /** Android 15+: public API. Returns null if the call failed. */
    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun classifyWithLauncherApps(handle: UserHandle): ProfileKind? = try {
        val info = app.getSystemService(LauncherApps::class.java).getLauncherUserInfo(handle)
        when (info?.userType) {
            // Hidden profiles such as the private space are not described to regular apps.
            null -> ProfileKind.HIDDEN
            UserManager.USER_TYPE_PROFILE_MANAGED -> ProfileKind.MANAGED
            else -> ProfileKind.NOT_MANAGED
        }
    } catch (e: RuntimeException) {
        Log.w(TAG, "LauncherApps could not describe $handle", e)
        null
    }

    /**
     * Used when LauncherApps cannot classify a profile (always on Android 11 to 14).
     * UserManager.getProfiles(int) and UserInfo.isManagedProfile() are hidden but allowed for
     * apps (greylisted). Returns null if they are not available.
     */
    @SuppressLint("DiscouragedPrivateApi", "PrivateApi", "SoonBlockedPrivateApi")
    private fun readManagedFlags(): Map<UserHandle, Boolean>? = try {
        val getProfiles = UserManager::class.java.getMethod("getProfiles", Int::class.javaPrimitiveType)
        val userInfo = Class.forName("android.content.pm.UserInfo")
        val getUserHandle = userInfo.getMethod("getUserHandle")
        val isManagedProfile = userInfo.getMethod("isManagedProfile")
        (getProfiles.invoke(userManager, myUserId()) as List<*>).filterNotNull().associate {
            getUserHandle.invoke(it) as UserHandle to (isManagedProfile.invoke(it) as Boolean)
        }
    } catch (e: ReflectiveOperationException) {
        Log.w(TAG, "Hidden profile lookup unavailable", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "Hidden profile lookup failed", e)
        null
    }

    companion object {
        private const val TAG = "WorkProfiles"

        /** This user's id (0 for most people), read without hidden APIs. */
        fun myUserId(): Int {
            val parcel = Parcel.obtain()
            return try {
                UserHandle.writeToParcel(Process.myUserHandle(), parcel)
                parcel.setDataPosition(0)
                parcel.readInt()
            } finally {
                parcel.recycle()
            }
        }
    }
}

/** Applies quiet mode to the profile [WorkProfiles] resolves, re-resolving it on every use. */
class AndroidQuietModeController(
    context: Context,
    private val profiles: WorkProfiles = WorkProfiles(context),
) : QuietModeController {
    private val app = context.applicationContext
    private val userManager = app.getSystemService(UserManager::class.java)

    override fun hasPermission(): Boolean = hasQuietModePermission(app)

    override fun findTarget(): TargetLookup = when (val state = profiles.resolve()) {
        is ProfileState.Selected -> TargetLookup.Found(UserProfileTarget(userManager, state.profile.handle))
        ProfileState.None -> TargetLookup.Missing
        else -> TargetLookup.Unconfirmed
    }

    private class UserProfileTarget(
        private val userManager: UserManager,
        private val handle: UserHandle,
    ) : WorkProfileTarget {
        override fun isPaused() = userManager.isQuietModeEnabled(handle)

        override fun requestPaused(paused: Boolean): Boolean = if (paused) {
            userManager.requestQuietModeEnabled(true, handle)
        } else {
            // Never let the system pop up a credential screen from the background.
            userManager.requestQuietModeEnabled(
                false,
                handle,
                UserManager.QUIET_MODE_DISABLE_ONLY_IF_CREDENTIAL_NOT_REQUIRED,
            )
        }
    }
}

/** Not in the public SDK's Manifest.permission, although apps may be granted it. */
const val MODIFY_QUIET_MODE = "android.permission.MODIFY_QUIET_MODE"

/** Always ask the system: a `pm grant` that did not work still exits successfully. */
fun hasQuietModePermission(context: Context): Boolean =
    context.checkSelfPermission(MODIFY_QUIET_MODE) == PackageManager.PERMISSION_GRANTED
