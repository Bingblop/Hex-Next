package com.hexnext.engine.frro

import com.hexnext.engine.arsc.StringPoolWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.TreeMap

/** One colour of a [FrroSpec]: `[package:]type/name`, ARGB value, optional qualifier (`night` …). */
public data class FrroColor(
    val resourceName: String,
    val argb: Int,
    val configuration: String? = null,
)

/**
 * One file (PNG) value of a [FrroSpec]. [ninePatch] must be false: nine-patch PNGs need the
 * aapt2 `npTc` compilation idmap2d performs, which [FrroWriter] does not implement.
 */
public class FrroBinary(
    public val resourceName: String,
    public val bytes: ByteArray,
    public val ninePatch: Boolean = false,
    public val configuration: String? = null,
)

/**
 * Everything one version 3 fabricated overlay holds, for [FrroWriter.write].
 *
 * @property ownerPackage owning package (`com.android.shell` for a root Shizuku service).
 * @property overlayName overlay name, the part after `owner:` in the overlay id.
 * @property targetPackage package whose resources are replaced.
 * @property targetOverlayable `<overlayable>` name, empty for none.
 */
public data class FrroSpec(
    val ownerPackage: String,
    val overlayName: String,
    val targetPackage: String,
    val targetOverlayable: String = "",
    val colors: List<FrroColor> = emptyList(),
    val binaries: List<FrroBinary> = emptyList(),
)

/**
 * Builds a fabricated overlay file in the layout described by [FrroFormat] (the layout of AOSP
 * `FabricatedOverlay::ToBinaryStream`, see `docs/reference/frro-binary-format.md`).
 *
 * Packages, types and entries are emitted sorted by name and configurations sorted within an
 * entry, like idmap2's `std::map`s, so the output does not depend on insertion order. Colour
 * overlays are byte-identical to Samsung's One UI 9.0 files (a parsed `SemWT_android.frro`
 * rewrites to the same bytes).
 *
 * File values are written as idmap2d writes them: the payloads are concatenated right after
 * the 16-byte version 3 header, and each value is a `TYPE_STRING` whose pool string is
 * `frro:/<frro path>?offset=<absolute offset>&size=<bytes>`. The URI names the file itself, so
 * the bytes are only valid at the path passed to [toByteArray].
 *
 * Resource names are `package:type/name` or `type/name` (the target package is assumed).
 *
 * @param version file version to write: 1 supports only integer values, 2 adds strings,
 *   3 adds file (binary) values.
 */
public class FrroWriter(
    private val packageName: String,
    private val name: String,
    private val targetPackage: String,
    private val targetOverlayable: String = "",
    private val version: Int = FrroFormat.CURRENT_VERSION,
) {
    private sealed class Value {
        data class Plain(val dataType: Int, val data: Int) : Value()
        data class Text(val value: String) : Value()
        class Binary(val bytes: ByteArray) : Value()
    }

    // package -> type -> entry -> config -> value, sorted like idmap2's std::map
    private val values = TreeMap<String, TreeMap<String, TreeMap<String, TreeMap<String, Value>>>>()

    init {
        require(version in 1..FrroFormat.CURRENT_VERSION) { "unsupported version $version" }
    }

    /** Sets a raw `Res_value` ([dataType], [data]) for [resourceName] in [configuration]. */
    public fun setResourceValue(resourceName: String, dataType: Int, data: Int, configuration: String = ""): FrroWriter =
        put(resourceName, configuration, Value.Plain(dataType, data))

    /** Sets an ARGB colour. */
    public fun setColor(resourceName: String, argb: Int, configuration: String = ""): FrroWriter =
        setResourceValue(resourceName, FrroFormat.TYPE_INT_COLOR_ARGB8, argb, configuration)

    /** Sets a string value (version 2+). */
    public fun setString(resourceName: String, value: String, configuration: String = ""): FrroWriter {
        require(version >= 2) { "string values need FRRO version 2+" }
        return put(resourceName, configuration, Value.Text(value))
    }

    /**
     * Sets a file value such as a PNG (version 3+). Nine-patch PNGs are rejected with
     * [NINE_PATCH_UNSUPPORTED]: idmap2d strips their border and adds an `npTc` chunk before
     * embedding them, which this writer does not do.
     */
    public fun setBinary(resourceName: String, bytes: ByteArray, isNinePatch: Boolean = false, configuration: String = ""): FrroWriter {
        require(version >= 3) { "file values need FRRO version 3+" }
        require(!isNinePatch) { "$resourceName: $NINE_PATCH_UNSUPPORTED" }
        return put(resourceName, configuration, Value.Binary(bytes.copyOf()))
    }

    private fun put(resourceName: String, configuration: String, value: Value): FrroWriter = apply {
        val pkg = resourceName.substringBefore(':', targetPackage).ifEmpty { targetPackage }
        val typeName = resourceName.substringAfter(':')
        val type = typeName.substringBefore('/', "")
        val entry = typeName.substringAfter('/', "")
        require(type.isNotEmpty() && entry.isNotEmpty()) { "resource name must be [package:]type/name: $resourceName" }
        require(version >= 2 || configuration.isEmpty()) { "configurations need FRRO version 2+" }
        values.getOrPut(pkg) { TreeMap() }.getOrPut(type) { TreeMap() }
            .getOrPut(entry) { TreeMap() }[configuration] = value
    }

    /** True when a file value was set, i.e. [toByteArray] needs the final path. */
    public val hasBinaries: Boolean
        get() = values.values.any { t -> t.values.any { e -> e.values.any { c -> c.values.any { it is Value.Binary } } } }

    /**
     * Serialises the overlay. [frroPath] is the absolute path the file will have on the device;
     * it is required when the overlay holds file values (their URIs embed it) and unused otherwise.
     */
    public fun toByteArray(frroPath: String? = null): ByteArray {
        if (hasBinaries) {
            require(frroPath != null && frroPath.startsWith("/")) { "file values need the absolute .frro path, got $frroPath" }
        }
        val strings = mutableListOf<String>()
        val binary = ByteArrayOutputStream()

        fun intern(s: String): Int = strings.indexOf(s).takeIf { it >= 0 } ?: strings.size.also { strings += s }

        fun ProtoWriter.value(v: Value) {
            when (v) {
                is Value.Plain -> varint(1, v.dataType.toLong() and 0xFFFFFFFFL).varint(2, v.data.toLong() and 0xFFFFFFFFL)
                is Value.Text -> varint(1, FrroFormat.TYPE_STRING.toLong()).varint(2, intern(v.value).toLong())
                is Value.Binary -> {
                    val offset = FrroFormat.V3_HEADER_SIZE.toLong() + binary.size()
                    binary.write(v.bytes)
                    val uri = binaryUri(frroPath!!, offset, v.bytes.size.toLong())
                    varint(1, FrroFormat.TYPE_STRING.toLong()).varint(2, intern(uri).toLong())
                }
            }
        }

        val proto = ProtoWriter()
        for ((pkg, types) in values) {
            proto.message(1) {
                string(1, pkg)
                for ((type, entries) in types) message(2) {
                    string(1, type)
                    for ((entry, configs) in entries) {
                        // one ResourceEntry per (name, configuration); default config has no field 3
                        for ((config, v) in configs) message(2) {
                            string(1, entry)
                            message(2) { value(v) }
                            string(3, config)
                        }
                    }
                }
            }
        }
        proto.string(2, name).string(3, packageName).string(4, targetPackage).string(5, targetOverlayable)
        val protoBytes = proto.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(le32(FrroFormat.MAGIC))
        out.write(le32(version))
        out.write(le32(FrroFormat.crc(version, protoBytes)))
        if (version >= 3) {
            out.write(le32(binary.size()))
            binary.writeTo(out)
        }
        if (version >= 2) {
            // Samsung writes the (empty) pool chunk even when there are no strings
            val pool = StringPoolWriter.write(strings)
            out.write(le32(pool.size))
            out.write(pool)
        }
        out.write(protoBytes)
        return out.toByteArray()
    }

    public companion object {
        /** Skip reason for nine-patch drawables. */
        public const val NINE_PATCH_UNSUPPORTED: String = "nine-patch needs npTc pre-compilation, not supported yet"

        /**
         * Writes [spec] as a version 3 `.frro` whose file values point into the file at
         * [frroPath] (absolute; the bytes must end up at exactly that path).
         *
         * @throws IllegalArgumentException for a nine-patch binary ([NINE_PATCH_UNSUPPORTED]),
         *   a malformed resource name or a relative path.
         */
        public fun write(spec: FrroSpec, frroPath: String): ByteArray {
            require(frroPath.startsWith("/")) { "frro path must be absolute: $frroPath" }
            val w = FrroWriter(spec.ownerPackage, spec.overlayName, spec.targetPackage, spec.targetOverlayable)
            spec.colors.forEach { w.setColor(it.resourceName, it.argb, it.configuration.orEmpty()) }
            spec.binaries.forEach { w.setBinary(it.resourceName, it.bytes, it.ninePatch, it.configuration.orEmpty()) }
            return w.toByteArray(frroPath)
        }

        /** `frro:/<path>?offset=<offset>&size=<size>`, the pool string of a file value (AOSP format). */
        public fun binaryUri(frroPath: String, offset: Long, size: Long): String =
            "${FrroFormat.URI_SCHEME}$frroPath?offset=$offset&size=$size"

        /**
         * Creates a writer pre-filled with every entry of [overlay]. File values are copied from
         * [source], the bytes [overlay] was parsed from; they are dropped when [source] is null.
         */
        public fun from(overlay: FabricatedOverlayFile, source: ByteArray? = null, version: Int = overlay.version): FrroWriter {
            val w = FrroWriter(overlay.packageName, overlay.name, overlay.targetPackage, overlay.targetOverlayable, version)
            for (e in overlay.entries) {
                val config = e.configuration.orEmpty()
                when {
                    e.binaryOffset != null -> if (source != null) {
                        w.setBinary(e.resourceName, FrroReader.readBinary(source, e)!!, configuration = config)
                    }
                    e.stringData != null -> w.setString(e.resourceName, e.stringData, config)
                    else -> w.setResourceValue(e.resourceName, e.dataType, e.data, config)
                }
            }
            return w
        }
    }

    private fun le32(v: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
}
