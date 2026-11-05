package com.hexnext.engine.compile

import com.hexnext.engine.map.PlannedValue
import com.hexnext.engine.map.Skipped
import java.security.MessageDigest
import kotlin.math.roundToInt

/**
 * One shape control as this phone supports it.
 *
 * @property rule the control's [ValueRule] from the One UI rules.
 * @property presentNames the rule's candidates the phone's scan lists (only these are written).
 * @property scanned true when the scan listed values for every package the candidates name; false
 *   means the scan predates value lists and a re-scan is needed.
 * @property stock the phone's own value of the first present candidate (default configuration),
 *   in [ValueRule.unit] for dimens; null when unknown.
 */
public data class ValueControl(
    val id: String,
    val rule: ValueRule,
    val presentNames: List<String>,
    val scanned: Boolean,
    val stock: Double? = null,
) {
    /** True when at least one candidate exists on the phone, so the control can be shown. */
    public val available: Boolean get() = presentNames.isNotEmpty()
}

/**
 * Plans the non-colour values (dimens, integers, bools, floats) of a target's value overlay
 * (`HexNextValues_<pkg>`) from [ThemeSpec.shapes] and [ThemeSpec.values].
 *
 * The rule that keeps System UI safe: a value is only ever planned for a `type/name` the phone's
 * scan lists ([TargetSnapshot.values]), in exactly the configurations the phone defines it, and
 * only for names the One UI rules allowlist ([QuickSettingsRules.allowsValue]).
 *
 * 1. Nothing for targets outside [VALUE_TARGETS] or when the rules list no values.
 * 2. Requests: [ThemeSpec.shapes] (each control expands to its rule's candidates), then
 *    [ThemeSpec.values], which win for the same name. Pack extras were merged into both by
 *    [NativeExtras] below the user's own entries.
 * 3. Skips: [REASON_VALUE_POLICY] (not allowlisted), [REASON_NOT_IN_TARGET] (the phone lacks the
 *    name), [REASON_RESCAN] (the scan has no value lists), [REASON_VALUE_TYPE] (the setting kind
 *    does not fit the stock value), [REASON_UNRESOLVED] (a scale of an unknown stock value).
 * 4. One value per device configuration. A scale multiplies each configuration's own value.
 * 5. Values are clamped to the rule's range; bools are written as `0xFFFFFFFF` / `0`.
 * 6. Values equal to stock are dropped, so a stock setting builds no overlay.
 */
public object ValuePlanner {
    /** Skip reason: the name is not on the version's value allowlist. */
    public const val REASON_VALUE_POLICY: String = "value not allowed by the One UI rules"

    /** Skip reason: the setting's kind does not fit the phone's value (a dimen stays a dimen). */
    public const val REASON_VALUE_TYPE: String = "value type does not match the phone's"

    /** Skip reason: a scale was asked of a value the scan could not resolve. */
    public const val REASON_UNRESOLVED: String = "stock value unknown"

    /** Skip reason: the scan has no value lists for the target; a re-scan fills them. */
    public const val REASON_RESCAN: String = "re-scan needed"

    /** Skip reason: the name is absent from the phone. */
    public const val REASON_NOT_IN_TARGET: String = ThemeCompiler.REASON_NOT_IN_TARGET

    /** Targets whose values the scan lists and value overlays may change. */
    public val VALUE_TARGETS: Set<String> = setOf("com.android.systemui", "android")

    /** Prefix of every value overlay name (it keeps the `HexNext` prefix, so Remove theme covers it). */
    public const val OVERLAY_PREFIX: String = "HexNextValues_"

    /** Lowest and highest factor a [ValueKind.SCALE] setting may use. */
    private const val MIN_SCALE = 0.25
    private const val MAX_SCALE = 4.0

    /** `HexNextValues_` + [packageName] with dots replaced by underscores. */
    public fun overlayName(packageName: String): String = OVERLAY_PREFIX + packageName.replace('.', '_')

    /** Result of [plan]: the values, the skips, how many were clamped, the controls that produced values. */
    public data class Plan(
        val values: List<PlannedValue>,
        val skipped: List<Skipped>,
        val clamped: Int,
        val controls: Set<String>,
        val requested: Int,
    ) {
        public companion object {
            public val EMPTY: Plan = Plan(emptyList(), emptyList(), 0, emptySet(), 0)
        }
    }

    private class Request(val setting: ValueSetting, val control: String, val rule: ValueRule?)

    /** The values of [pkg] (see [ValuePlanner]). */
    public fun plan(pkg: String, snap: TargetSnapshot, spec: ThemeSpec, rules: OneUiRules): Plan {
        val qs = rules.quickSettings
        if (pkg !in VALUE_TARGETS || (qs.values.isEmpty() && qs.valueNames.isEmpty())) return Plan.EMPTY
        if (!spec.hasValues()) return Plan.EMPTY
        val skipped = ArrayList<Skipped>()
        val requests = LinkedHashMap<String, Request>()
        for ((id, setting) in spec.shapes.toSortedMap()) {
            val rule = qs.values[id] ?: continue
            for (name in rule.names) {
                if (name.substringBefore(':', "") != pkg) continue
                requests[name] = Request(setting, id, rule)
            }
        }
        for ((key, setting) in spec.values.toSortedMap()) {
            if (key.substringBefore(':', "") != pkg) continue
            if (!qs.allowsValue(key)) {
                skipped += Skipped(key, REASON_VALUE_POLICY)
                continue
            }
            requests[key] = Request(setting, "values", qs.valueRuleFor(key)?.second)
        }
        if (requests.isEmpty()) return Plan(emptyList(), skipped, 0, emptySet(), 0)
        if (snap.values.isEmpty()) {
            requests.keys.mapTo(skipped) { Skipped(it, REASON_RESCAN) }
            return Plan(emptyList(), skipped, 0, emptySet(), requests.size)
        }

        val out = ArrayList<PlannedValue>()
        val controls = LinkedHashSet<String>()
        var clamped = 0
        for ((name, request) in requests) {
            val typeName = name.substringAfter(':')
            val stock = snap.values[typeName]
            if (stock.isNullOrEmpty()) {
                skipped += Skipped(name, REASON_NOT_IN_TARGET)
                continue
            }
            for (sv in stock) {
                val display = name + if (sv.config.isEmpty()) "" else " [${sv.config}]"
                when (val r = compute(request.setting, sv, request.rule)) {
                    is Outcome.Skip -> skipped += Skipped(display, r.reason)
                    is Outcome.Value -> {
                        if (r.clamped) clamped++
                        if (isStock(sv, r.dataType, r.data)) continue
                        out += PlannedValue(name, r.dataType, r.data, sv.config, request.control)
                        controls += request.control
                    }
                }
            }
        }
        return Plan(out, skipped, clamped, controls, requests.size)
    }

    private sealed class Outcome {
        class Value(val dataType: Int, val data: Int, val clamped: Boolean) : Outcome()
        class Skip(val reason: String) : Outcome()
    }

    private fun compute(setting: ValueSetting, stock: ScannedValue, rule: ValueRule?): Outcome {
        val t = stock.dataType
        val unresolved = t == ValueTypes.TYPE_REFERENCE
        val type = Outcome.Skip(REASON_VALUE_TYPE)
        return when (setting.kind) {
            ValueKind.UNKNOWN -> type
            ValueKind.DIMEN -> {
                val number = setting.number ?: return type
                if (!unresolved && t != ValueTypes.TYPE_DIMENSION) return type
                val (v, c) = clamp(number, rule, setting.unit.code)
                Outcome.Value(ValueTypes.TYPE_DIMENSION, Dimens.encode(v, setting.unit.code), c)
            }
            ValueKind.SCALE -> {
                val factor = (setting.number ?: return type).coerceIn(MIN_SCALE, MAX_SCALE)
                when (t) {
                    ValueTypes.TYPE_REFERENCE -> Outcome.Skip(REASON_UNRESOLVED)
                    ValueTypes.TYPE_DIMENSION -> {
                        val unit = Dimens.unitCode(stock.data)
                        val (v, c) = clamp(Dimens.value(stock.data) * factor, rule, unit)
                        Outcome.Value(ValueTypes.TYPE_DIMENSION, Dimens.encode(v, unit), c)
                    }
                    ValueTypes.TYPE_FLOAT -> {
                        val (v, c) = clamp(Float.fromBits(stock.data) * factor, rule, null)
                        Outcome.Value(ValueTypes.TYPE_FLOAT, v.toFloat().toRawBits(), c)
                    }
                    else -> type
                }
            }
            ValueKind.INTEGER -> {
                val number = setting.number ?: return type
                if (!unresolved && t != ValueTypes.TYPE_INT_DEC && t != ValueTypes.TYPE_INT_HEX) return type
                val (v, c) = clamp(number, rule, null)
                Outcome.Value(ValueTypes.TYPE_INT_DEC, v.roundToInt(), c)
            }
            ValueKind.BOOL -> {
                val flag = setting.flag ?: return type
                if (!unresolved && t != ValueTypes.TYPE_INT_BOOLEAN) return type
                Outcome.Value(ValueTypes.TYPE_INT_BOOLEAN, if (flag) ValueTypes.BOOL_TRUE else 0, false)
            }
            ValueKind.FLOAT -> {
                val number = setting.number ?: return type
                if (!unresolved && t != ValueTypes.TYPE_FLOAT) return type
                val (v, c) = clamp(number, rule, null)
                Outcome.Value(ValueTypes.TYPE_FLOAT, v.toFloat().toRawBits(), c)
            }
        }
    }

    /** [value] clamped to [rule]'s range; for dimens only when [unitCode] is the rule's unit. */
    private fun clamp(value: Double, rule: ValueRule?, unitCode: Int?): Pair<Double, Boolean> {
        if (rule == null || (unitCode != null && unitCode != rule.unit.code)) return value to false
        var v = value
        rule.min?.let { if (v < it) v = it }
        rule.max?.let { if (v > it) v = it }
        return v to (v != value)
    }

    private fun isStock(stock: ScannedValue, dataType: Int, data: Int): Boolean {
        if (!stock.resolved) return false
        val stockType = if (stock.dataType == ValueTypes.TYPE_INT_HEX) ValueTypes.TYPE_INT_DEC else stock.dataType
        if (stockType != dataType) return false
        return when (dataType) {
            ValueTypes.TYPE_DIMENSION -> Dimens.sameSize(stock.data, data)
            ValueTypes.TYPE_INT_BOOLEAN -> (stock.data != 0) == (data != 0)
            else -> stock.data == data
        }
    }

    /**
     * SHA-256 (lower-case hex) of the sorted values (`v|config|name|type|data`); equal hashes mean
     * the value overlay does not need to be rebuilt. Separate from the colour
     * [ThemeCompiler.contentHash], so adding values never rebuilds a colour overlay.
     */
    public fun valueHash(values: List<PlannedValue>): String {
        val lines = values.map { "v|${it.configuration}|${it.resourceName}|%02x|%08x".format(it.dataType, it.data) }.sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(lines.joinToString("\n").toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Every shape control of [rules] as [snapshot] supports it, in rule order. A control without
     * [ValueControl.available] names is hidden by the UI (or, when not [ValueControl.scanned],
     * shown as "Rescan needed").
     */
    public fun availableControls(snapshot: DeviceSnapshot?, rules: OneUiRules): Map<String, ValueControl> {
        val out = LinkedHashMap<String, ValueControl>()
        for ((id, rule) in rules.quickSettings.values) {
            val packages = rule.names.map { it.substringBefore(':', "") }.distinct()
            val scanned = snapshot != null && packages.all { snapshot.targets[it]?.values?.isNotEmpty() == true }
            val present = rule.names.filter { name ->
                snapshot?.targets?.get(name.substringBefore(':', ""))?.values?.containsKey(name.substringAfter(':')) == true
            }
            val stock = present.firstNotNullOfOrNull { name ->
                val values = snapshot?.targets?.get(name.substringBefore(':'))?.values?.get(name.substringAfter(':')).orEmpty()
                val v = values.firstOrNull { it.config.isEmpty() } ?: values.firstOrNull()
                when {
                    v == null -> null
                    v.dataType == ValueTypes.TYPE_DIMENSION && Dimens.unitCode(v.data) == rule.unit.code -> Dimens.value(v.data)
                    v.dataType == ValueTypes.TYPE_INT_DEC || v.dataType == ValueTypes.TYPE_INT_HEX -> v.data.toDouble()
                    v.dataType == ValueTypes.TYPE_FLOAT -> Float.fromBits(v.data).toDouble()
                    else -> null
                }
            }
            out[id] = ValueControl(id, rule, present, scanned, stock)
        }
        return out
    }
}
