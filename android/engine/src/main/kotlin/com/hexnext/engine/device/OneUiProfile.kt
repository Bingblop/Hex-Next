package com.hexnext.engine.device

/**
 * Samsung One UI version of a device.
 *
 * @property major One UI major version (7, 8, 9 …).
 * @property minor One UI minor version (`8.5` → 5).
 * @property sdkInt Android `ro.build.version.sdk`, 0 when unknown.
 * @property semPlatformInt `SEM_PLATFORM_INT` (`ro.build.version.sep`, e.g. 160000), or 0.
 * @property semInt `SEM_INT` (`ro.build.version.sem`), or 0.
 */
public data class OneUiVersion(
    val major: Int,
    val minor: Int,
    val sdkInt: Int,
    val semPlatformInt: Int,
    val semInt: Int = 0,
) : Comparable<OneUiVersion> {
    /** `"9.0"`, `"8.5"`. */
    public val name: String get() = "$major.$minor"

    override fun compareTo(other: OneUiVersion): Int = compareValuesBy(this, other, { it.major }, { it.minor })

    override fun toString(): String = "One UI $name (SDK $sdkInt)"

    public companion object {
        public const val PROP_ONEUI: String = "ro.build.version.oneui"
        public const val PROP_SEP: String = "ro.build.version.sep"
        public const val PROP_SEM: String = "ro.build.version.sem"
        public const val PROP_SDK: String = "ro.build.version.sdk"

        /** `SEM_PLATFORM_INT` of One UI 1.0; each major version adds 10000. */
        private const val SEP_ONEUI_BASE = 90000

        /**
         * Reads the version from system properties.
         *
         * `ro.build.version.oneui` is `major*10000 + minor*100 + patch` (`90000` → 9.0,
         * `80500` → 8.5). When it is absent, `ro.build.version.sep` is used
         * (`SEM_PLATFORM_INT = 90000 + major*10000 + minor*100`, `160000` → 7.0).
         * Returns null when neither is present, i.e. not a One UI device.
         */
        public fun fromProps(props: Map<String, String>): OneUiVersion? {
            val sdk = props[PROP_SDK].toIntOrZero()
            val sep = props[PROP_SEP].toIntOrZero()
            val sem = props[PROP_SEM].toIntOrZero()
            val oneUi = props[PROP_ONEUI].toIntOrZero()
            val code = when {
                oneUi > 0 -> oneUi
                sep > SEP_ONEUI_BASE -> sep - SEP_ONEUI_BASE
                else -> return null
            }
            return OneUiVersion(code / 10000, (code / 100) % 100, sdk, sep, sem)
        }

        private fun String?.toIntOrZero(): Int = this?.trim()?.toIntOrNull() ?: 0
    }
}

/**
 * What Hex Next needs to know about the device to pick a rule set.
 *
 * @property version null when the device is not running One UI.
 * @property model `ro.product.model`, `""` when unknown.
 */
public data class OneUiProfile(
    val version: OneUiVersion?,
    val model: String,
) {
    /** True for the One UI majors Hex Next supports ([SUPPORTED_MAJORS]). */
    public val isSupported: Boolean get() = version != null && version.major in SUPPORTED_MAJORS

    /** Name of the rule file under `engine/src/main/resources/oneui/`, e.g. `9.json`. */
    public val ruleFile: String? get() = version?.let { "${it.major}.json" }

    public companion object {
        /** One UI majors with rule files. */
        public val SUPPORTED_MAJORS: IntRange = 7..9

        /** Builds a profile from system properties. */
        public fun fromProps(props: Map<String, String>): OneUiProfile =
            OneUiProfile(OneUiVersion.fromProps(props), props["ro.product.model"].orEmpty())

        /** Parses `getprop` output (`[key]: [value]` per line) into a map. */
        public fun parseGetprop(output: String): Map<String, String> {
            val line = Regex("""^\[(.+?)]:\s*\[(.*)]\s*$""")
            return output.lineSequence().mapNotNull { line.matchEntire(it.trim())?.destructured }
                .associate { (k, v) -> k to v }
        }
    }
}
