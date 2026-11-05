package com.hexnext.engine.compile

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * What a [ValueSetting] holds. [UNKNOWN] is what an unknown kind written by a newer build reads
 * as ([SpecJson] coerces it); the planner skips it, so it never makes a theme unreadable.
 */
@Serializable
public enum class ValueKind {
    UNKNOWN,

    /** An absolute size: [ValueSetting.number] in [ValueSetting.unit]. */
    DIMEN,

    /** A factor of the phone's own value, applied per configuration ([ValueSetting.number]). */
    SCALE,

    /** A whole number ([ValueSetting.number], rounded). */
    INTEGER,

    /** A switch ([ValueSetting.flag]). */
    BOOL,

    /** A plain float ([ValueSetting.number]), for `<item format="float" type="dimen">`. */
    FLOAT,
}

/** Units of a [ValueKind.DIMEN] value. */
@Serializable
public enum class DimenUnit(public val code: Int, public val suffix: String) {
    DP(1, "dp"),
    PX(0, "px"),
    SP(2, "sp"),
}

/**
 * One non-colour value the user (or a pack option) wants. A flat, lenient class rather than a
 * sealed one: a kind added later reads as [ValueKind.UNKNOWN] instead of breaking the theme.
 */
@Serializable
public data class ValueSetting(
    val kind: ValueKind = ValueKind.UNKNOWN,
    val number: Double? = null,
    val unit: DimenUnit = DimenUnit.DP,
    val flag: Boolean? = null,
) {
    public companion object {
        /** An absolute size of [value] [unit]. */
        public fun dimen(value: Double, unit: DimenUnit = DimenUnit.DP): ValueSetting = ValueSetting(ValueKind.DIMEN, value, unit)

        /** [factor] times the phone's own value. */
        public fun scale(factor: Double): ValueSetting = ValueSetting(ValueKind.SCALE, factor)

        /** A whole number. */
        public fun integer(value: Int): ValueSetting = ValueSetting(ValueKind.INTEGER, value.toDouble())

        /** A switch. */
        public fun bool(value: Boolean): ValueSetting = ValueSetting(ValueKind.BOOL, flag = value)
    }
}

/**
 * Ids of the Quick panel shape controls ([ThemeSpec.shapes] keys). Each id is mapped to resource
 * names by the One UI rules ([QuickSettingsRules.values]); code never hard-codes the names.
 */
public object ShapeControls {
    /** Corner radius of the Quick panel tiles. */
    public const val TILE_CORNER: String = "tileCorner"

    /** Corner radius of the Quick panel itself. */
    public const val PANEL_CORNER: String = "panelCorner"

    /** Thickness (height) of the brightness and volume sliders. */
    public const val SLIDER_THICKNESS: String = "sliderThickness"

    /** Corner radius of the notification cards. */
    public const val NOTIFICATION_CORNER: String = "notificationCorner"

    /** The controls the Quick panel step shows, in order. */
    public val QUICK_PANEL: List<String> = listOf(TILE_CORNER, PANEL_CORNER, SLIDER_THICKNESS, NOTIFICATION_CORNER)
}

/** `Res_value` data types a value overlay may hold, and the allowlist the service enforces too. */
public object ValueTypes {
    public const val TYPE_REFERENCE: Int = 0x01
    public const val TYPE_FLOAT: Int = 0x04
    public const val TYPE_DIMENSION: Int = 0x05
    public const val TYPE_INT_DEC: Int = 0x10
    public const val TYPE_INT_HEX: Int = 0x11
    public const val TYPE_INT_BOOLEAN: Int = 0x12

    /** Types a value overlay may write: float, dimension, decimal and hex integer, boolean. */
    public val ALLOWED: Set<Int> = setOf(TYPE_FLOAT, TYPE_DIMENSION, TYPE_INT_DEC, TYPE_INT_HEX, TYPE_INT_BOOLEAN)

    /** Data of a true boolean, as aapt2 writes it. */
    public const val BOOL_TRUE: Int = -1
}

/**
 * `TYPE_DIMENSION` complex values: unit in bits 0-3, radix in bits 4-5, a signed 24-bit
 * mantissa in bits 8-31 (AOSP `TypedValue.complexToFloat`, aapt2 `ResourceUtils::TryParseFloat`).
 */
public object Dimens {
    private val UNITS = arrayOf("px", "dp", "sp", "pt", "in", "mm")
    private val RADIX_MULTS = doubleArrayOf(1.0 / (1 shl 8), 1.0 / (1 shl 15), 1.0 / (1 shl 23), 1.0 / (1L shl 31))
    private val PARSE = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*(px|dp|dip|sp|pt|in|mm)\s*$""")

    /** The complex value of [value] [unit], in the smallest radix that holds it, as aapt2 does. */
    public fun encode(value: Float, unit: DimenUnit): Int = encode(value.toDouble(), unit.code)

    /** [encode] for a raw unit code (0 px, 1 dp, 2 sp, 3 pt, 4 in, 5 mm). */
    public fun encode(value: Double, unitCode: Int): Int {
        val negative = value < 0
        val magnitude = abs(value)
        val bits = (magnitude * (1 shl 23) + 0.5).toLong()
        val (radix, shift) = when {
            bits and 0x7fffffL == 0L -> 0 to 23
            bits and -0x800000L == 0L -> 3 to 0
            bits and -0x80000000L == 0L -> 2 to 8
            bits and -0x8000000000L == 0L -> 1 to 16
            else -> 0 to 23
        }
        var mantissa = ((bits shr shift) and 0xffffffL).toInt()
        if (negative) mantissa = (-mantissa) and 0xffffff
        return (mantissa shl 8) or (radix shl 4) or (unitCode and 0xf)
    }

    /** The number of [raw], in its own unit. */
    public fun value(raw: Int): Double = (raw and 0xFFFFFF00.toInt()).toDouble() * RADIX_MULTS[(raw shr 4) and 0x3]

    /** The unit code of [raw] (0 px, 1 dp, 2 sp …). */
    public fun unitCode(raw: Int): Int = raw and 0xf

    /** [raw] as text: `16.0dp`, `3.5px` (the scan's `names-dimen.txt` format). */
    public fun format(raw: Int): String {
        val value = (raw and 0xFFFFFF00.toInt()).toFloat() * RADIX_MULTS[(raw shr 4) and 0x3].toFloat()
        val unit = UNITS.getOrNull(raw and 0xF) ?: "unit${raw and 0xF}"
        return "$value$unit"
    }

    /** `16.0dp`, `3.5px`, `12sp`, `4dip` → complex value; null when the text is not a dimension. */
    public fun parse(text: String): Int? {
        val m = PARSE.find(text) ?: return null
        val number = m.groupValues[1].toDoubleOrNull() ?: return null
        val unit = when (val u = m.groupValues[2]) {
            "dip" -> 1
            else -> UNITS.indexOf(u)
        }
        return encode(number, unit)
    }

    /** True when [a] and [b] are the same size in the same unit (radix may differ). */
    public fun sameSize(a: Int, b: Int): Boolean =
        unitCode(a) == unitCode(b) && (value(a) * 1000).roundToLong() == (value(b) * 1000).roundToLong()
}

/**
 * One value the device scan found: [config] (`""` for default, `land`, `sw600dp-v13` …),
 * `Res_value` [dataType] and [data]. A reference keeps its id in [refId]; its [dataType] and
 * [data] are the resolved value when the scan could resolve it, else [ValueTypes.TYPE_REFERENCE].
 */
public data class ScannedValue(val config: String, val dataType: Int, val data: Int, val refId: Int? = null) {
    /** True when the stock value is a known number (not an unresolved reference). */
    public val resolved: Boolean get() = dataType != ValueTypes.TYPE_REFERENCE
}

/** Parses the scan's `names-<type>.txt` files (written by the app's device scanner). */
public object ValueNames {
    private val OTHER = Regex("""^Other\(dataType=(\d+), data=(-?\d+)\)$""")
    private val INT = Regex("""^-?\d+$""")
    private val FLOAT = Regex("""^-?\d+(\.\d+)?([eE]-?\d+)?f$""")
    private val REF = Regex("""^@0x([0-9a-fA-F]{1,8})$""")

    /** The file types the scan writes. */
    public val TYPES: List<String> = listOf("dimen", "integer", "bool")

    /**
     * Parses `name<TAB>config<TAB>value[<TAB>resolved]` lines of a `names-<type>.txt`
     * (`default` is the default configuration). Keys are `type/name`. Lines that cannot be read
     * are dropped and counted in [Parsed.dropped].
     */
    public fun parse(type: String, text: String): Parsed {
        val out = LinkedHashMap<String, MutableList<ScannedValue>>()
        var dropped = 0
        for (line in text.lineSequence()) {
            if (line.isBlank()) continue
            val cols = line.split('\t')
            if (cols.size < 3 || cols.size > 4 || cols[0].isBlank()) {
                dropped++
                continue
            }
            val config = cols[1].trim().let { if (it == "default") "" else it }
            val ref = REF.find(cols[2].trim())?.groupValues?.get(1)?.toLong(16)?.toInt()
            val value = if (ref != null) {
                val resolved = cols.getOrNull(3)?.let(::decode)
                if (resolved == null) ScannedValue(config, ValueTypes.TYPE_REFERENCE, ref, ref)
                else ScannedValue(config, resolved.first, resolved.second, ref)
            } else {
                decode(cols[2])?.let { ScannedValue(config, it.first, it.second) }
            }
            if (value == null) {
                dropped++
                continue
            }
            out.getOrPut("$type/${cols[0].trim()}") { ArrayList() } += value
        }
        return Parsed(out, dropped)
    }

    /** `(dataType, data)` of one value column; null when it is not a value this reader knows. */
    public fun decode(text: String): Pair<Int, Int>? {
        val t = text.trim()
        Dimens.parse(t)?.let { return ValueTypes.TYPE_DIMENSION to it }
        if (t == "true") return ValueTypes.TYPE_INT_BOOLEAN to ValueTypes.BOOL_TRUE
        if (t == "false") return ValueTypes.TYPE_INT_BOOLEAN to 0
        if (INT.matches(t)) return t.toLongOrNull()?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.let { ValueTypes.TYPE_INT_DEC to it.toInt() }
        if (FLOAT.matches(t)) return t.dropLast(1).toFloatOrNull()?.let { ValueTypes.TYPE_FLOAT to it.toRawBits() }
        OTHER.find(t)?.let { m ->
            val type = m.groupValues[1].toInt()
            val data = m.groupValues[2].toLong().toInt()
            if (type == ValueTypes.TYPE_FLOAT) return type to data
        }
        return null
    }

    /** Result of [parse]: `type/name` → values, and the count of unreadable lines. */
    public data class Parsed(val values: Map<String, List<ScannedValue>>, val dropped: Int)
}
