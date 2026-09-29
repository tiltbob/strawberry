package io.github.tiltbob.strawberry.platform

import android.content.pm.LauncherApps
import android.content.pm.LauncherUserInfo
import android.os.Parcel
import android.os.UserHandle
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLauncherApps

/** Robolectric does not implement LauncherApps.getLauncherUserInfo(); this answers from [userTypes]. */
@Implements(LauncherApps::class)
class ShadowLauncherAppsWithUserTypes : ShadowLauncherApps() {
    @Implementation(minSdk = 35)
    protected fun getLauncherUserInfo(user: UserHandle): LauncherUserInfo? {
        if (unavailable) throw IllegalStateException("LauncherApps is not available")
        val type = userTypes[user] ?: return null
        val parcel = Parcel.obtain()
        return try {
            parcel.writeString(type)
            parcel.writeInt(0)
            parcel.setDataPosition(0)
            LauncherUserInfo.CREATOR.createFromParcel(parcel)
        } finally {
            parcel.recycle()
        }
    }

    companion object {
        val userTypes = mutableMapOf<UserHandle, String>()

        /** Makes the call fail, so the classification falls back to the hidden API. */
        var unavailable = false

        fun reset() {
            userTypes.clear()
            unavailable = false
        }
    }
}
