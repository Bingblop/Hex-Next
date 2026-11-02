package com.hexnext.themer.safety

import android.content.Context
import android.content.pm.PackageManager
import com.hexnext.themer.BuildConfig

/**
 * The Play build (com.hexnext.themer) and the owner build (com.hexnext.themer.owner) both name
 * their overlays HexNext*, share the font module and save Samsung's original overlay state each
 * on their own side. Running both on one phone would let one remove or overwrite the other's
 * theme without restoring Samsung's overlays, so each build refuses to change the theme while
 * the other is installed.
 */
object OtherBuild {
    private const val OWNER_SUFFIX = ".owner"

    /** Package name of the other build, worked out from this build's application id. */
    val packageName: String =
        BuildConfig.APPLICATION_ID.let { if (it.endsWith(OWNER_SUFFIX)) it.removeSuffix(OWNER_SUFFIX) else it + OWNER_SUFFIX }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    /** What to tell the user when a theme change is refused. */
    val refusal: String
        get() = if (BuildConfig.APPLICATION_ID.endsWith(OWNER_SUFFIX)) {
            "Uninstall the Play build of Hex Next first: two builds cannot theme one phone"
        } else {
            "Uninstall Hex Next Owner first: two builds cannot theme one phone"
        }
}
