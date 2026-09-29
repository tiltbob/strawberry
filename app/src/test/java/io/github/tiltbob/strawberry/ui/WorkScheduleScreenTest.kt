package io.github.tiltbob.strawberry.ui

import android.os.Looper
import android.os.Process
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import io.github.tiltbob.strawberry.platform.ProfileCandidate
import io.github.tiltbob.strawberry.platform.ProfileKind
import io.github.tiltbob.strawberry.platform.ProfileState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WorkScheduleScreenTest {
    private val work = ProfileCandidate(Process.myUserHandle(), 10, ProfileKind.MANAGED, false, "Work Profile A")
    private val other = work.copy(serial = 11, label = "Work Profile B")

    @Test
    fun statusNamesTheOnlyProfileWithoutOfferingToChangeIt() {
        val texts = shownTexts(ProfileState.Selected(work, alternatives = emptyList()))
        assertTrue(texts.toString(), "Profile: Work Profile A" in texts)
        assertFalse(texts.toString(), "Change" in texts)
    }

    @Test
    fun statusOffersToChangeTheProfileWhenThereAreOthers() {
        val texts = shownTexts(ProfileState.Selected(work, alternatives = listOf(other)))
        assertTrue(texts.toString(), "Profile: Work Profile A" in texts)
        assertTrue(texts.toString(), "Change" in texts)
    }

    private fun shownTexts(profile: ProfileState): List<String> {
        val state = ScreenState.load(RuntimeEnvironment.getApplication()).copy(profile = profile)
        Robolectric.buildActivity(ComponentActivity::class.java).use { controller ->
            val activity = controller.setup().get()
            activity.setContent { WorkScheduleScreen(state, object : ScreenActions {}) }
            shadowOf(Looper.getMainLooper()).idle()
            return texts(activity.window.decorView)
        }
    }

    private fun texts(view: View): List<String> {
        if (view is ViewRootForTest) {
            val found = mutableListOf<String>()
            fun walk(node: SemanticsNode) {
                node.config.getOrNull(SemanticsProperties.Text)?.forEach { found += it.text }
                node.children.forEach(::walk)
            }
            walk(view.semanticsOwner.unmergedRootSemanticsNode)
            return found
        }
        if (view !is ViewGroup) return emptyList()
        return (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
    }
}
