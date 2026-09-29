package io.github.tiltbob.strawberry

import android.app.AlarmManager
import io.github.tiltbob.strawberry.platform.AndroidAlarmScheduler
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
class UnpauseActivityTest {
    @Test
    fun openingItCancelsTheBootRetry() {
        val app = RuntimeEnvironment.getApplication()
        AndroidAlarmScheduler(app).armRetry(Instant.now().plusSeconds(60))

        Robolectric.buildActivity(UnpauseActivity::class.java).use { it.setup() }

        // Otherwise the retry could post "Tap to turn on work apps" while the user enters the PIN.
        assertTrue(shadowOf(app.getSystemService(AlarmManager::class.java)).scheduledAlarms.isEmpty())
    }
}
