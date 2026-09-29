package io.github.tiltbob.strawberry.platform

import android.os.UserHandle
import android.os.UserManager
import io.github.tiltbob.strawberry.core.ApplyResult
import io.github.tiltbob.strawberry.core.Reason
import io.github.tiltbob.strawberry.schedule.Schedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowUserManager
import java.time.DayOfWeek
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowLauncherAppsWithUserTypes::class])
class WorkProfilesTest {
    private val app = RuntimeEnvironment.getApplication()
    private val userManager = app.getSystemService(UserManager::class.java)
    private val prefs = Prefs(app)
    private val profiles = WorkProfiles(app, prefs)

    @Before
    fun setUp() = ShadowLauncherAppsWithUserTypes.reset()

    /** Adds a profile to user 0; [userType] is what LauncherApps reports (null: hidden). */
    private fun addProfile(id: Int, flags: Int, userType: String?): UserHandle {
        shadowOf(userManager).addProfile(0, id, "Profile $id", flags)
        val handle = userManager.getUserForSerialNumber(id.toLong())
        if (userType != null) ShadowLauncherAppsWithUserTypes.userTypes[handle] = userType
        return handle
    }

    private fun addWorkProfile(id: Int) =
        addProfile(id, ShadowUserManager.FLAG_MANAGED_PROFILE, UserManager.USER_TYPE_PROFILE_MANAGED)

    @Test
    fun noOtherProfiles() {
        assertEquals(ProfileState.None, profiles.resolve())
    }

    @Test
    fun otherKindsOfProfilesAreNeverUsed() {
        addProfile(11, ShadowUserManager.FLAG_PROFILE, "android.os.usertype.profile.CLONE")
        // Hidden profiles such as the private space are not described by LauncherApps.
        addProfile(12, ShadowUserManager.FLAG_PROFILE, null)
        assertEquals(
            listOf(ProfileKind.NOT_MANAGED, ProfileKind.HIDDEN),
            profiles.candidates().map { it.kind },
        )
        assertEquals(ProfileState.None, profiles.resolve())
    }

    @Test
    fun fallsBackToUserInfoWhenLauncherAppsFails() {
        ShadowLauncherAppsWithUserTypes.unavailable = true
        addProfile(10, ShadowUserManager.FLAG_MANAGED_PROFILE, null)
        addProfile(11, ShadowUserManager.FLAG_PROFILE, null)
        assertEquals(
            listOf(ProfileKind.MANAGED, ProfileKind.NOT_MANAGED),
            profiles.candidates().map { it.kind },
        )
        assertEquals(10L, (profiles.resolve() as ProfileState.Selected).profile.serial)
    }

    @Test
    fun learnedWorkProfileIsTrusted() {
        ShadowLauncherAppsWithUserTypes.unavailable = true
        val handle = addProfile(10, ShadowUserManager.FLAG_MANAGED_PROFILE, null)
        profiles.learnManaged(handle)
        assertEquals(setOf(10L), prefs.learnedManagedSerials)
    }

    @Test
    fun singleWorkProfileIsSelectedAutomatically() {
        addWorkProfile(10)

        val state = profiles.resolve() as ProfileState.Selected

        assertEquals(10L, state.profile.serial)
        assertEquals(ProfileKind.MANAGED, state.profile.kind)
        assertEquals(10L, prefs.selectedProfileSerial)
        assertFalse(prefs.selectedProfileConfirmed)
    }

    @Test
    fun severalWorkProfilesNeedAChoice() {
        addWorkProfile(10)
        addWorkProfile(150)

        val state = profiles.resolve() as ProfileState.NeedsChoice
        assertEquals(listOf(10L, 150L), state.candidates.map { it.serial })

        profiles.choose(150)
        val selected = profiles.resolve() as ProfileState.Selected
        assertEquals(150L, selected.profile.serial)
        assertEquals(listOf(10L), selected.alternatives.map { it.serial })
    }

    @Test
    fun removedProfileIsNotUsed() {
        prefs.selectProfile(42, confirmedByUser = true)
        assertEquals(ProfileState.None, profiles.resolve())
    }

    @Test
    fun reconcilerPausesAndUnpausesTheWorkProfile() {
        val handle = addWorkProfile(10)
        val reconciler = createReconciler(app)

        // No days: always paused. Without the adb grant nothing happens.
        prefs.schedule = Schedule(enabled = true, days = emptySet())
        reconciler.run(Reason.SCHEDULE_SAVED)
        assertEquals(ApplyResult.NoPermission, prefs.status?.lastApplyResult)
        assertFalse(userManager.isQuietModeEnabled(handle))

        shadowOf(app).grantPermissions(MODIFY_QUIET_MODE)
        reconciler.run(Reason.SCHEDULE_SAVED)
        assertEquals(ApplyResult.Ok, prefs.status?.lastApplyResult)
        assertTrue(userManager.isQuietModeEnabled(handle))

        // Around the clock every day: work apps on.
        prefs.schedule = Schedule(
            enabled = true,
            start = LocalTime.MIDNIGHT,
            end = LocalTime.MIDNIGHT,
            days = DayOfWeek.entries.toSet(),
        )
        reconciler.run(Reason.SCHEDULE_SAVED)
        assertEquals(ApplyResult.Ok, prefs.status?.lastApplyResult)
        assertFalse(userManager.isQuietModeEnabled(handle))
    }
}
