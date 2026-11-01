package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.device.OneUiProfile
import com.hexnext.engine.frro.FabricatedOverlayFile

/**
 * What the device scan found for one target package.
 *
 * @property installed true only when the package is installed for the current user and can be
 *   themed.
 * @property debloated true when the package is not installed for the current user but the
 *   system still has traces of it (uninstalled for the user, APK left on the system image, or
 *   Samsung's SemWT overlay reporting `STATE_MISSING_TARGET`). A debloated target always has
 *   `installed = false`; the pair `installed = false, debloated = false` means absent.
 * @property table the target's parsed `resources.arsc`, null when it could not be read.
 * @property semwt Samsung's `SemWT_<pkg>` fabricated overlay, null when there is none.
 * @property versionCode installed version code, null when unknown.
 */
public data class TargetSnapshot(
    val packageName: String,
    val installed: Boolean,
    val debloated: Boolean = false,
    val table: ResourceTable? = null,
    val semwt: FabricatedOverlayFile? = null,
    val versionCode: Long? = null,
)

/**
 * Result of a device scan.
 *
 * @property targets per target package.
 * @property systemShades the 65 `android:color/system_*` colours active at scan time, keyed
 *   `system_accent1_500`; the reference palette for [com.hexnext.engine.palette.RoleInference].
 */
public data class DeviceSnapshot(
    val oneUi: OneUiProfile,
    val targets: Map<String, TargetSnapshot>,
    val systemShades: Map<String, Int>,
)
