package com.hexnext.engine.palette

/** A named seed colour. */
public data class ColorPreset(val name: String, val argb: Int)

/** Built-in seed colours: Material 500 hues plus a few Samsung-like tones. */
public object ColorPresets {
    private fun p(name: String, rgb: Int): ColorPreset = ColorPreset(name, rgb or 0xFF000000.toInt())

    /** All presets in display order. */
    public val ALL: List<ColorPreset> = listOf(
        p("Red", 0xF44336),
        p("Pink", 0xE91E63),
        p("Purple", 0x9C27B0),
        p("Deep Purple", 0x673AB7),
        p("Indigo", 0x3F51B5),
        p("Blue", 0x2196F3),
        p("Light Blue", 0x03A9F4),
        p("Cyan", 0x00BCD4),
        p("Teal", 0x009688),
        p("Green", 0x4CAF50),
        p("Light Green", 0x8BC34A),
        p("Lime", 0xCDDC39),
        p("Yellow", 0xFFEB3B),
        p("Amber", 0xFFC107),
        p("Orange", 0xFF9800),
        p("Deep Orange", 0xFF5722),
        p("Brown", 0x795548),
        p("Grey", 0x9E9E9E),
        p("Blue Grey", 0x607D8B),
        p("Galaxy Blue", 0x1A5FD0),
        p("Mint", 0x3EB489),
        p("Lavender", 0x9D8BD8),
        p("Graphite", 0x4A4D52),
        p("Cream", 0xEFDFC2),
    )

    /** Preset named [name] (case-insensitive), or `null`. */
    public fun byName(name: String): ColorPreset? = ALL.firstOrNull { it.name.equals(name, ignoreCase = true) }
}
