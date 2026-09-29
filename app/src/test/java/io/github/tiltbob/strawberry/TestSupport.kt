package io.github.tiltbob.strawberry

import android.os.Looper
import io.github.tiltbob.strawberry.platform.Background
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.TimeUnit

/** Lets queued broadcasts run, then waits until the background thread has finished its work. */
fun idleEverything() {
    repeat(3) {
        shadowOf(Looper.getMainLooper()).idle()
        Background.executor.submit {}.get(10, TimeUnit.SECONDS)
    }
    shadowOf(Looper.getMainLooper()).idle()
}
