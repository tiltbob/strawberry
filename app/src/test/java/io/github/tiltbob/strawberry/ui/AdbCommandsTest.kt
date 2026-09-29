package io.github.tiltbob.strawberry.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AdbCommandsTest {
    @Test
    fun grantForTheMainUserHasNoUserFlag() {
        assertEquals(
            "adb shell pm grant io.github.tiltbob.strawberry android.permission.MODIFY_QUIET_MODE",
            AdbCommands.grant("io.github.tiltbob.strawberry", 0),
        )
    }

    @Test
    fun grantForAnotherUserNamesIt() {
        assertEquals(
            "adb shell pm grant --user 10 io.github.tiltbob.strawberry android.permission.MODIFY_QUIET_MODE",
            AdbCommands.grant("io.github.tiltbob.strawberry", 10),
        )
    }

    @Test
    fun batteryWhitelist() {
        assertEquals(
            "adb shell dumpsys deviceidle whitelist +io.github.tiltbob.strawberry",
            AdbCommands.batteryWhitelist("io.github.tiltbob.strawberry"),
        )
    }
}
