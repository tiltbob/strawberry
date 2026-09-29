package io.github.tiltbob.strawberry

import android.provider.Settings
import androidx.compose.runtime.MutableState
import io.github.tiltbob.strawberry.core.Reason
import io.github.tiltbob.strawberry.platform.Prefs
import io.github.tiltbob.strawberry.schedule.Schedule
import io.github.tiltbob.strawberry.ui.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.DayOfWeek
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    private val prefs = Prefs(RuntimeEnvironment.getApplication())

    @Test
    fun launchesAndReconcilesOnResume() {
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            assertNotNull(controller.setup().get())
            idleEverything()
            assertEquals(Reason.APP_OPENED, prefs.status?.lastReason)
        }
    }

    @Test
    fun confirmingAnUnchangedScheduleDoesNotForceIt() {
        prefs.schedule = Schedule(enabled = true)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            idleEverything()
            prefs.lastDesiredWorkOn = true

            activity.onScheduleChange(shownSchedule(activity))
            idleEverything()

            assertEquals(Reason.APP_OPENED, prefs.status?.lastReason)
            assertEquals(true, prefs.lastDesiredWorkOn)
        }
    }

    @Test
    fun openingTheAppRemembersTheBootCount() {
        Settings.Global.putInt(RuntimeEnvironment.getApplication().contentResolver, Settings.Global.BOOT_COUNT, 3)
        Robolectric.buildActivity(MainActivity::class.java).use { controller ->
            controller.setup()
            idleEverything()
        }
        assertEquals(3, prefs.lastBootCount)
    }

    @Test
    fun anOlderCopyOfTheScreenShowsEditsMadeInANewerOne() {
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        idleEverything()
        first.pause().stop()

        // A second copy of the screen changes the end time.
        Robolectric.buildActivity(MainActivity::class.java).use { second ->
            second.setup()
            idleEverything()
            second.get().onScheduleChange(shownSchedule(second.get()).copy(end = LocalTime.of(17, 0)))
            idleEverything()
            second.pause().stop().destroy()
        }

        first.restart().resume()
        idleEverything()
        val shown = shownSchedule(first.get())
        assertEquals(LocalTime.of(17, 0), shown.end)

        // Editing in the first copy keeps the other copy's change.
        first.get().onScheduleChange(shown.copy(days = shown.days + DayOfWeek.SATURDAY))
        idleEverything()
        assertEquals(LocalTime.of(17, 0), prefs.schedule.end)
        assertEquals(Schedule.WEEKDAYS + DayOfWeek.SATURDAY, prefs.schedule.days)
        first.pause().stop().destroy()
    }

    private fun shownSchedule(activity: MainActivity): Schedule {
        val field = MainActivity::class.java.getDeclaredField("state\$delegate").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (field.get(activity) as MutableState<ScreenState?>).value!!.schedule
    }
}
