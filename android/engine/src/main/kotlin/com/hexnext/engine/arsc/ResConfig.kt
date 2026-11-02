package com.hexnext.engine.arsc

/**
 * The decoded subset of `ResTable_config` that Hex Next needs, plus the raw bytes.
 *
 * [qualifiers] renders the configuration in aapt qualifier order (`night-xxhdpi-v26`). When the
 * configuration uses fields this class does not decode (touchscreen, keyboard, navigation,
 * locale script/variant, colour mode, numbering system …) the string is instead
 * `"cfg:" + hex(raw)` so that distinct configurations never collapse into the same string.
 */
public class ResConfig(
    public val mcc: Int = 0,
    public val mnc: Int = 0,
    public val language: String = "",
    public val country: String = "",
    public val orientation: Int = 0,
    public val density: Int = 0,
    public val sdkVersion: Int = 0,
    public val screenLayout: Int = 0,
    public val uiMode: Int = 0,
    public val smallestScreenWidthDp: Int = 0,
    public val screenWidthDp: Int = 0,
    public val screenHeightDp: Int = 0,
    public val screenLayout2: Int = 0,
    /** True when a field outside the decoded subset is non-zero. */
    public val hasUndecodedFields: Boolean = false,
    raw: ByteArray = ByteArray(0),
) {
    private val rawBytes: ByteArray = raw.copyOf()

    /** A copy of the raw `ResTable_config` bytes as stored in the table. */
    public val raw: ByteArray get() = rawBytes.copyOf()

    /** True when the uiMode night bits say `night`. */
    public val isNight: Boolean get() = uiMode and UI_MODE_NIGHT_MASK == UI_MODE_NIGHT_YES

    /** The density qualifier alone (`xxhdpi`, `nodpi`, `420dpi`), or `""` when unset. */
    public val densityQualifier: String get() = densityName(density)

    /** aapt-style qualifier string; `""` for the default configuration. */
    public val qualifiers: String by lazy { buildQualifiers() }

    override fun toString(): String = qualifiers
    override fun equals(other: Any?): Boolean = other is ResConfig && other.qualifiers == qualifiers
    override fun hashCode(): Int = qualifiers.hashCode()

    private fun buildQualifiers(): String {
        if (hasUndecodedFields) return "cfg:" + rawBytes.joinToString("") { "%02x".format(it) }
        val parts = mutableListOf<String>()
        if (mcc != 0) parts += "mcc$mcc"
        if (mnc != 0) parts += if (mnc == MNC_ZERO) "mnc00" else "mnc$mnc"
        if (language.isNotEmpty()) parts += language
        if (country.isNotEmpty()) parts += "r$country"
        when (screenLayout and LAYOUTDIR_MASK) {
            LAYOUTDIR_LTR -> parts += "ldltr"
            LAYOUTDIR_RTL -> parts += "ldrtl"
        }
        if (smallestScreenWidthDp != 0) parts += "sw${smallestScreenWidthDp}dp"
        if (screenWidthDp != 0) parts += "w${screenWidthDp}dp"
        if (screenHeightDp != 0) parts += "h${screenHeightDp}dp"
        when (screenLayout and SCREENSIZE_MASK) {
            1 -> parts += "small"
            2 -> parts += "normal"
            3 -> parts += "large"
            4 -> parts += "xlarge"
        }
        when (screenLayout and SCREENLONG_MASK) {
            0x10 -> parts += "notlong"
            0x20 -> parts += "long"
        }
        when (screenLayout2 and 0x03) {
            1 -> parts += "notround"
            2 -> parts += "round"
        }
        when (orientation) {
            1 -> parts += "port"
            2 -> parts += "land"
            3 -> parts += "square"
        }
        when (uiMode and 0x0f) {
            2 -> parts += "desk"
            3 -> parts += "car"
            4 -> parts += "television"
            5 -> parts += "appliance"
            6 -> parts += "watch"
            7 -> parts += "vrheadset"
        }
        when (uiMode and UI_MODE_NIGHT_MASK) {
            0x10 -> parts += "notnight"
            UI_MODE_NIGHT_YES -> parts += "night"
        }
        densityName(density).takeIf { it.isNotEmpty() }?.let { parts += it }
        if (sdkVersion != 0) parts += "v$sdkVersion"
        return parts.joinToString("-")
    }

    public companion object {
        /** The default (empty) configuration. */
        public val DEFAULT: ResConfig = ResConfig()

        private const val UI_MODE_NIGHT_MASK = 0x30
        private const val UI_MODE_NIGHT_YES = 0x20
        private const val LAYOUTDIR_MASK = 0xC0
        private const val LAYOUTDIR_LTR = 0x40
        private const val LAYOUTDIR_RTL = 0x80
        private const val SCREENSIZE_MASK = 0x0F
        private const val SCREENLONG_MASK = 0x30
        private const val MNC_ZERO = 0xFFFF

        /** Maps a `density` value to its qualifier (`xxhdpi`, `anydpi`, `420dpi`), `""` for 0. */
        public fun densityName(density: Int): String = when (density) {
            0 -> ""
            120 -> "ldpi"
            160 -> "mdpi"
            213 -> "tvdpi"
            240 -> "hdpi"
            320 -> "xhdpi"
            480 -> "xxhdpi"
            640 -> "xxxhdpi"
            0xFFFE -> "anydpi"
            0xFFFF -> "nodpi"
            else -> "${density}dpi"
        }

        /**
         * Decodes `ResTable_config` from [raw] (the bytes starting at its `size` field).
         * Fields beyond `raw.size` are treated as zero.
         */
        public fun decode(raw: ByteArray): ResConfig {
            fun u8(off: Int) = if (off < raw.size) raw[off].toInt() and 0xFF else 0
            fun u16(off: Int) = u8(off) or (u8(off + 1) shl 8)
            fun chars(off: Int): String {
                val a = u8(off)
                val b = u8(off + 1)
                if (a == 0 && b == 0) return ""
                return if (a and 0x80 == 0) "${a.toChar()}${b.toChar()}" else "\u0000" // packed 3-letter
            }

            val language = chars(8)
            val country = chars(10)
            var undecoded = language == "\u0000" || country == "\u0000"
            // touchscreen, keyboard, navigation, inputFlags, grammatical inflection
            undecoded = undecoded || listOf(13, 16, 17, 18, 19).any { u8(it) != 0 }
            undecoded = undecoded || u16(20) != 0 || u16(22) != 0 || u16(26) != 0 // screen size, minor version
            undecoded = undecoded || (36 until 48).any { u8(it) != 0 } // locale script + variant
            undecoded = undecoded || (u8(48) and 0x03.inv()) != 0 || u8(49) != 0 // screenLayout2 extras, colour mode
            undecoded = undecoded || (53 until raw.size).any { u8(it) != 0 } // numbering system and newer fields
            return ResConfig(
                mcc = u16(4),
                mnc = u16(6),
                language = if (undecoded) "" else language,
                country = if (undecoded) "" else country,
                orientation = u8(12),
                density = u16(14),
                sdkVersion = u16(24),
                screenLayout = u8(28),
                uiMode = u8(29),
                smallestScreenWidthDp = u16(30),
                screenWidthDp = u16(32),
                screenHeightDp = u16(34),
                screenLayout2 = u8(48),
                hasUndecodedFields = undecoded,
                raw = raw,
            )
        }
    }
}
