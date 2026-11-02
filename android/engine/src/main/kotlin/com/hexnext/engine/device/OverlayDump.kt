package com.hexnext.engine.device

/**
 * One resource an overlay replaces, from a `Mapping:` line of `cmd overlay dump`.
 *
 * One UI 9.0 prints both configurations of a resource on one line, default first:
 *
 * ```
 * 0x7f060020 -> color 0xff0b4d5f    0x7f060020 -> color 0xff6ed4ef (color/actionbar_text_color)
 * ```
 *
 * The second value is the `night` configuration (that is the only non-default configuration
 * in Samsung's SemWT files, and the order matches the `.frro`: default, then `night`).
 * Lines with a single value have no night value.
 *
 * @property resId target resource id (`0x7f060020`).
 * @property type resource type (`color`).
 * @property name resource entry name (`actionbar_text_color`).
 * @property defaultValue the default-configuration value without its kind (`0xff0b4d5f`).
 * @property nightValue the `night` value, or null when the line has only one value.
 * @property kind the value kind printed before the default value (`color`, `dimension`,
 *   `file` …); `raw` when the value had no kind word.
 * @property nightKind kind of [nightValue] when it differs from [kind], else null.
 */
public data class MappedResource(
    val resId: Int,
    val type: String,
    val name: String,
    val defaultValue: String,
    val nightValue: String?,
    val kind: String,
    val nightKind: String? = null,
) {
    /** `type/name`. */
    public val typeAndName: String get() = "$type/$name"

    /** ARGB of [defaultValue] when [kind] is `color`, else null. */
    public val defaultColor: Int? get() = if (kind == KIND_COLOR) parseHex(defaultValue) else null

    /** ARGB of [nightValue] when it is a colour, else null. */
    public val nightColor: Int? get() = if ((nightKind ?: kind) == KIND_COLOR) nightValue?.let(::parseHex) else null

    public companion object {
        public const val KIND_COLOR: String = "color"
        public const val KIND_RAW: String = "raw"

        internal fun parseHex(value: String): Int? =
            value.removePrefix("#").removePrefix("0x").toLongOrNull(16)?.takeIf { it in 0..0xFFFFFFFFL }?.toInt()
    }
}

/**
 * One overlay from `cmd overlay dump [<package>[:<name>]]`: the info block, plus, when the
 * output contains the overlay's `IDMAP OF` section, its debug warnings and mappings.
 *
 * This is the primary way Hex Next learns which resources Samsung themes and with which
 * colours: it needs only `cmd overlay` (shell or root), not read access to
 * the `.frro` files in `/data/resource-cache`, and the names come back already resolved against the target.
 *
 * @property overlayId `package:name`, e.g. `android:SemWT_com.android.settings`.
 * @property userId user id from the block header (`...:0 {`).
 * @property packageName `mPackageName` (owner; `android` for SemWT overlays).
 * @property overlayName `mOverlayName`.
 * @property targetPackage `mTargetPackageName`.
 * @property targetOverlayable `mTargetOverlayableName`, `""` when none (all SemWT overlays).
 * @property baseCodePath `mBaseCodePath` (the `.frro` for fabricated overlays).
 * @property state `mState`, e.g. `STATE_ENABLED`, `STATE_MISSING_TARGET`, `STATE_NO_IDMAP`.
 * @property isEnabled `mIsEnabled`.
 * @property isMutable `mIsMutable`, null when not printed.
 * @property priority `mPriority`, null when not printed or not numeric
 *   (SemWT overlays sit at `Integer.MAX_VALUE`, 2147483647).
 * @property isFabricated `mIsFabricated`.
 * @property fields every `mXxx` field of the info block, raw.
 * @property entries mapped resources from the `Mapping:` section (empty without an IDMAP section).
 * @property missing distinct `package:type/name` the idmap could not find in the target
 *   (`W failed to find resource '…'`): names Samsung themes that this target build lacks.
 */
public data class OverlayDump(
    val overlayId: String,
    val userId: Int,
    val packageName: String,
    val overlayName: String,
    val targetPackage: String,
    val targetOverlayable: String,
    val baseCodePath: String,
    val state: String,
    val isEnabled: Boolean,
    val isMutable: Boolean?,
    val priority: Int?,
    val isFabricated: Boolean,
    val fields: Map<String, String> = emptyMap(),
    val entries: List<MappedResource> = emptyList(),
    val missing: Set<String> = emptySet(),
) {
    /** True when the info block says the overlay sits at `Integer.MAX_VALUE`. */
    public val isMaxPriority: Boolean get() = priority == Int.MAX_VALUE

    /** `targetPackage:type/name` of [entry]. */
    public fun resourceName(entry: MappedResource): String = "$targetPackage:${entry.typeAndName}"

    public companion object {
        private val HEADER = Regex("""^(\S+):(\d+)\s*\{\s*$""")
        private val FIELD = Regex("""^\s+(m[A-Za-z]+)\.*:\s?(.*)$""")
        private val IDMAP = Regex("""^IDMAP OF (\S+)\s*$""")
        private val MISSING = Regex("""failed to find resource '([^']+)'""")
        private val MAP_START = Regex("""(?:^|\s)(0x[0-9a-fA-F]+)\s*->\s*""")
        private val NAME_SUFFIX = Regex("""\s*\(([^()]*)\)\s*$""")

        /** Parses the first overlay of [text], or null when it holds none. */
        public fun parse(text: String): OverlayDump? = parseAll(text).firstOrNull()

        /**
         * Parses every overlay in [text] (`cmd overlay dump <package>` prints one info block per
         * overlay). Text that is not part of a block, an IDMAP section or a mapping (shell
         * banners, Samsung's appended `Color palette history` log) is ignored.
         */
        public fun parseAll(text: String): List<OverlayDump> {
            val builders = LinkedHashMap<String, Builder>()
            var block: Builder? = null // inside `{ … }`
            var idmap: Builder? = null // inside `IDMAP OF …`
            var inMapping = false
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd()
                val inBlock = block
                if (inBlock != null) {
                    if (line.trim() == "}") {
                        block = null
                    } else {
                        val f = FIELD.find(line)
                        if (f != null) inBlock.fields[f.groupValues[1]] = f.groupValues[2].trim()
                    }
                    continue
                }
                val header = HEADER.find(line)
                if (header != null) {
                    block = builders.getOrPut(header.groupValues[1]) { Builder(header.groupValues[1]) }
                        .also { it.userId = header.groupValues[2].toInt() }
                    idmap = null
                    inMapping = false
                    continue
                }
                val idmapHeader = IDMAP.find(line)
                if (idmapHeader != null) {
                    idmap = builders.getOrPut(idmapHeader.groupValues[1]) { Builder(idmapHeader.groupValues[1]) }
                    inMapping = false
                    continue
                }
                val current = idmap ?: continue
                if (line.isBlank()) {
                    inMapping = false
                    continue
                }
                if (line.trim() == "Mapping:") {
                    inMapping = true
                    continue
                }
                val missing = MISSING.find(line)
                if (missing != null) {
                    current.missing += missing.groupValues[1]
                    continue
                }
                if (inMapping) {
                    val entry = parseMapping(line)
                    if (entry != null) current.entries += entry else inMapping = false
                }
            }
            return builders.values.map { it.build() }
        }

        /** Parses one `Mapping:` line; null when it is not one. */
        public fun parseMapping(line: String): MappedResource? {
            val suffix = NAME_SUFFIX.find(line) ?: return null
            val label = suffix.groupValues[1].substringBefore(" -> ").trim()
            val type = label.substringBefore('/', "")
            val name = label.substringAfter('/', "")
            if (type.isEmpty() || name.isEmpty()) return null
            val body = line.substring(0, suffix.range.first)
            val starts = MAP_START.findAll(body).toList()
            if (starts.isEmpty()) return null
            val values = starts.mapIndexed { i, m ->
                val end = if (i + 1 < starts.size) starts[i + 1].range.first else body.length
                m.groupValues[1] to body.substring(m.range.last + 1, end).trim()
            }
            val resId = MappedResource.parseHex(values[0].first) ?: return null
            val (kind, defaultValue) = splitKind(values[0].second)
            val night = values.getOrNull(1)?.let { splitKind(it.second) }
            return MappedResource(
                resId = resId,
                type = type,
                name = name,
                defaultValue = defaultValue,
                nightValue = night?.second,
                kind = kind,
                nightKind = night?.first?.takeIf { it != kind },
            )
        }

        /** `color 0xff0b4d5f` → (`color`, `0xff0b4d5f`); `0x00001801` → (`raw`, `0x00001801`). */
        private fun splitKind(value: String): Pair<String, String> {
            val word = value.substringBefore(' ')
            val rest = value.substringAfter(' ', "").trim()
            return if (rest.isNotEmpty() && word.all { it.isLetter() || it == '_' }) word to rest
            else MappedResource.KIND_RAW to value
        }
    }

    private class Builder(val overlayId: String) {
        var userId = 0
        val fields = LinkedHashMap<String, String>()
        val entries = ArrayList<MappedResource>()
        val missing = LinkedHashSet<String>()

        fun build() = OverlayDump(
            overlayId = overlayId,
            userId = userId,
            packageName = fields["mPackageName"] ?: overlayId.substringBefore(':'),
            overlayName = fields["mOverlayName"] ?: overlayId.substringAfter(':'),
            targetPackage = fields["mTargetPackageName"].orEmpty(),
            targetOverlayable = fields["mTargetOverlayableName"].orEmpty(),
            baseCodePath = fields["mBaseCodePath"].orEmpty(),
            state = fields["mState"].orEmpty(),
            isEnabled = fields["mIsEnabled"] == "true",
            isMutable = fields["mIsMutable"]?.let { it == "true" },
            priority = fields["mPriority"]?.toIntOrNull(),
            isFabricated = fields["mIsFabricated"] == "true",
            fields = fields.toMap(),
            entries = entries.toList(),
            missing = missing.toSet(),
        )
    }
}
