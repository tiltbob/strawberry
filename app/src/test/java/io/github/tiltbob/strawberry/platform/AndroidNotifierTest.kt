package io.github.tiltbob.strawberry.platform

import android.Manifest
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import io.github.tiltbob.strawberry.MainActivity
import io.github.tiltbob.strawberry.UnpauseActivity
import io.github.tiltbob.strawberry.core.ApplyResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class AndroidNotifierTest {
    private val app = RuntimeEnvironment.getApplication()
    private val manager = shadowOf(app.getSystemService(NotificationManager::class.java))
    private val notifier = AndroidNotifier(app)

    @Before
    fun setUp() = shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

    private fun postedIntent(): Intent = shadowOf(manager.allNotifications.single().contentIntent).savedIntent

    @Test
    fun problemOpensTheAppLikeTheLauncher() {
        notifier.showProblem(ApplyResult.NoPermission)

        // Matching the launcher intent brings back an open screen instead of stacking a copy.
        val intent = postedIntent()
        assertEquals(ComponentName(app, MainActivity::class.java), intent.component)
        assertEquals(Intent.ACTION_MAIN, intent.action)
        assertTrue(intent.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun needsCredentialOpensTheUnpauseScreen() {
        notifier.showNeedsCredential()

        val intent = postedIntent()
        assertEquals(ComponentName(app, UnpauseActivity::class.java), intent.component)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
}
