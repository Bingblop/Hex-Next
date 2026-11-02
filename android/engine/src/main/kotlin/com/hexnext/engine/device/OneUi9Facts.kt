package com.hexnext.engine.device

/**
 * Facts observed on the first real One UI 9.0 device: a Galaxy SM-S948U1, Android 17 (SDK 37),
 * KernelSU root, Shizuku running as root. Source: `docs/device/oneui9-sm-s948u1/hexnext-diag.txt`
 * (read-only diagnostics). These are Kotlin constants until they are merged into the One UI 9
 * JSON rules.
 */
public object OneUi9Facts {
    /** Owner package of every `SemWT_*` overlay (`mPackageName`). */
    public const val SEMWT_OWNER: String = "android"

    /** Overlay name prefix of Samsung's wallpaper-theme (Monet) overlays. */
    public const val SEMWT_PREFIX: String = "SemWT_"

    /** `mPriority` of every SemWT overlay: `Integer.MAX_VALUE`. */
    public const val SEMWT_PRIORITY: Int = Int.MAX_VALUE

    /** `mTargetOverlayableName` of every SemWT overlay: empty (they target no `<overlayable>`). */
    public const val SEMWT_TARGET_OVERLAYABLE: String = ""

    /** SemWT overlays are fabricated (`mIsFabricated true`) and mutable (`mIsMutable true`). */
    public const val SEMWT_IS_FABRICATED: Boolean = true
    public const val SEMWT_IS_MUTABLE: Boolean = true

    /** SemWT `.frro` files live in `/data/resource-cache`, not `/data/overlays`. */
    public const val SEMWT_FRRO_DIR: String = "/data/resource-cache"

    /**
     * `mBaseCodePath` of a SemWT overlay: `/data/resource-cache/android-SemWT_<pkg>-XXXX.frro`
     * (four random characters). Older copies with other suffixes can linger beside the live one,
     * so take the path from `cmd overlay dump`, not from a directory listing.
     */
    public val SEMWT_FRRO_PATH: Regex = Regex("""^/data/resource-cache/android-SemWT_(.+)-[A-Za-z0-9]{4}\.frro$""")

    /** SemWT overlays that target `android` with Monet shades rather than an app's colours. */
    public val MONET_PALETTE_OVERLAYS: Set<String> = setOf("SemWT_MonetPalette", "SemWT_G_MonetPalette")

    /** `mState` values seen on the device. */
    public const val STATE_ENABLED: String = "STATE_ENABLED"

    /** The target is not installed (debloated); `cmd overlay list` shows the overlay as `---`. */
    public const val STATE_MISSING_TARGET: String = "STATE_MISSING_TARGET"

    /** The target is installed but has no idmap for the overlay; also listed as `---`. */
    public const val STATE_NO_IDMAP: String = "STATE_NO_IDMAP"

    /** `android:color/system_accent1_500` from `cmd overlay lookup` on this device. */
    public const val LOOKUP_SYSTEM_ACCENT1_500: Int = 0xff008396.toInt()

    /**
     * Targets with an enabled (`[x]`) `android:SemWT_<target>` overlay in `cmd overlay list`
     * (43 enabled SemWT overlays minus `SemWT_MonetPalette`, which targets `android` with Monet
     * shades; `SemWT_G_MonetPalette` was disabled). 74 more SemWT overlays were listed as `---`.
     */
    public val ENABLED_SEMWT_TARGETS: List<String> = listOf(
        "android",
        "com.android.bluetooth",
        "com.android.settings",
        "com.android.settings.intelligence",
        "com.android.systemui",
        "com.samsung.accessibility",
        "com.samsung.accessory.budsunitemgr",
        "com.samsung.android.app.aodservice",
        "com.samsung.android.app.cameraassistant",
        "com.samsung.android.app.contacts",
        "com.samsung.android.app.dressroom",
        "com.samsung.android.app.galaxyraw",
        "com.samsung.android.app.interpreter",
        "com.samsung.android.app.reminder",
        "com.samsung.android.app.smartcapture",
        "com.samsung.android.app.soundpicker",
        "com.samsung.android.app.telephonyui",
        "com.samsung.android.biometrics.app.setting",
        "com.samsung.android.bixby.agent",
        "com.samsung.android.callassistant",
        "com.samsung.android.dialer",
        "com.samsung.android.honeyboard",
        "com.samsung.android.incallui",
        "com.samsung.android.lool",
        "com.samsung.android.privacydashboard",
        "com.samsung.android.scpm",
        "com.samsung.android.secsoundpicker",
        "com.samsung.android.service.tagservice",
        "com.samsung.android.smartmirroring",
        "com.samsung.android.smartsuggestions",
        "com.samsung.android.video",
        "com.samsung.app.newtrim",
        "com.sec.android.app.camera",
        "com.sec.android.app.launcher",
        "com.sec.android.app.myfiles",
        "com.sec.android.app.shealth",
        "com.sec.android.app.soundalive",
        "com.sec.android.app.voicenote",
        "com.sec.android.daemonapp",
        "com.sec.android.gallery3d",
        "com.sec.android.mimage.photoretouching",
        "com.sec.unifiedwfc",
    )

    /** `android:SemWT_<target>`. */
    public fun semWtOverlayId(target: String): String = "$SEMWT_OWNER:$SEMWT_PREFIX$target"

    /**
     * The target package encoded in a SemWT overlay id or name (`android:SemWT_<pkg>` or
     * `SemWT_<pkg>`), or null for other overlays and the Monet palette overlays.
     */
    public fun semWtTarget(overlayIdOrName: String): String? {
        val name = overlayIdOrName.substringAfter(':')
        if (!name.startsWith(SEMWT_PREFIX) || name in MONET_PALETTE_OVERLAYS) return null
        return name.removePrefix(SEMWT_PREFIX).ifEmpty { null }
    }

    /** True when [state] (`mState`) means the overlay's target package is not installed. */
    public fun isDebloated(state: String): Boolean = state.trim() == STATE_MISSING_TARGET
}
