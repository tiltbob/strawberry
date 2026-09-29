package io.github.tiltbob.strawberry

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/** Runs against Robolectric's android-all jar (defaults to targetSdk). */
@RunWith(RobolectricTestRunner::class)
class RobolectricSmokeTest {
    @Test
    fun appLabelResolvesFromResources() {
        val app = RuntimeEnvironment.getApplication()
        assertEquals("Work Schedule", app.getString(R.string.app_name))
        assertEquals(36, Build.VERSION.SDK_INT)
    }

    @Test
    fun mainActivityLaunches() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            assertNotNull(controller.setup().get())
        }
    }
}
