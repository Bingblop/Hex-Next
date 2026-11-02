package com.hexnext.themer.device

import android.os.Build
import com.hexnext.engine.device.OneUiProfile
import com.hexnext.engine.device.OneUiVersion
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Whether this device can run Hex Next: Samsung One UI 7 or later on Android 15 (SDK 35) or later.
 */
sealed class Compatibility {
    /** Samsung One UI [CompatibilityCheck.MIN_ONE_UI_MAJOR]+ on SDK [CompatibilityCheck.MIN_SDK]+. */
    object Supported : Compatibility()

    /**
     * A One UI device whose Android or One UI is too old for the fabricated-overlay APIs this app
     * uses; the old Hex Installer covers it.
     *
     * @property androidSdk `Build.VERSION.SDK_INT`.
     * @property oneUi "One UI 6.1" style name, null when the One UI version could not be read.
     */
    data class TooOld(val androidSdk: Int, val oneUi: String?) : Compatibility()

    /** Not a Samsung One UI ROM (AOSP, HyperOS, …): nothing to theme the One UI way. */
    object NotOneUi : Compatibility()
}

/**
 * Reads the device facts the gate needs once per process: manufacturer, `Build.VERSION.SDK_INT`
 * and the One UI version from `ro.build.version.oneui` (`ro.build.version.sep` as the fallback
 * the engine's [OneUiVersion.fromProps] knows), through `android.os.SystemProperties` by
 * reflection and, failing that, `getprop`.
 */
object CompatibilityCheck {
    const val MIN_SDK: Int = 35
    const val MIN_ONE_UI_MAJOR: Int = 7

    /** The Play Store page of the old Hex Installer, for devices this app does not support. */
    const val HEX_INSTALLER_URL: String = "https://play.google.com/store/apps/details?id=project.vivid.hex.bodhi"

    private val props: Map<String, String> by lazy {
        val keys = listOf(OneUiVersion.PROP_ONEUI, OneUiVersion.PROP_SEP, OneUiVersion.PROP_SEM)
        val out = LinkedHashMap<String, String>()
        for (key in keys) {
            val value = systemProperty(key) ?: getprop(key)
            if (!value.isNullOrBlank()) out[key] = value.trim()
        }
        out[OneUiVersion.PROP_SDK] = Build.VERSION.SDK_INT.toString()
        out
    }

    /** The One UI version of this device, null when it is not a One UI build. */
    val oneUiVersion: OneUiVersion? by lazy { OneUiVersion.fromProps(props) }

    /** The parsed profile (version + model) for engine calls that want one. */
    val profile: OneUiProfile by lazy { OneUiProfile(oneUiVersion, Build.MODEL.orEmpty()) }

    private val result: Compatibility by lazy {
        val samsung = Build.MANUFACTURER.equals("samsung", ignoreCase = true)
        val version = oneUiVersion
        when {
            !samsung || version == null -> Compatibility.NotOneUi
            Build.VERSION.SDK_INT < MIN_SDK || version.major < MIN_ONE_UI_MAJOR ->
                Compatibility.TooOld(Build.VERSION.SDK_INT, oneUiName())
            else -> Compatibility.Supported
        }
    }

    fun check(): Compatibility = result

    /** "One UI 9.0", or null when the version is unknown. */
    fun oneUiName(): String? = oneUiVersion?.let { "One UI ${it.name}" }

    /** "Android 17 · One UI 9.0" (the One UI part is left out when unknown). */
    fun versionLine(): String = listOfNotNull("Android ${Build.VERSION.RELEASE}", oneUiName()).joinToString(" · ")

    private fun systemProperty(key: String): String? = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun getprop(key: String): String? = runCatching {
        val process = ProcessBuilder("getprop", key).redirectErrorStream(true).start()
        try {
            BufferedReader(InputStreamReader(process.inputStream)).use { it.readLine() }?.trim()?.takeIf { it.isNotBlank() }
        } finally {
            process.destroy()
        }
    }.getOrNull()
}
