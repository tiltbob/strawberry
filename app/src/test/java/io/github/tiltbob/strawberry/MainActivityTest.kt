package io.github.tiltbob.strawberry

import io.github.tiltbob.strawberry.core.Reason
import io.github.tiltbob.strawberry.platform.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    @Test
    fun launchesAndReconcilesOnResume() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            assertNotNull(controller.setup().get())
            idleEverything()
            assertEquals(Reason.APP_OPENED, Prefs(RuntimeEnvironment.getApplication()).status?.lastReason)
        }
    }
}
