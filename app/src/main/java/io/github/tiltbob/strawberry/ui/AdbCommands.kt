package io.github.tiltbob.strawberry.ui

/** The adb commands shown in the setup checklist (and in the README). */
object AdbCommands {
    /** `pm grant` targets user 0 unless told otherwise. */
    fun grant(packageName: String, userId: Int): String {
        val user = if (userId != 0) "--user $userId " else ""
        return "adb shell pm grant $user$packageName android.permission.MODIFY_QUIET_MODE"
    }

    fun batteryWhitelist(packageName: String): String =
        "adb shell dumpsys deviceidle whitelist +$packageName"
}
