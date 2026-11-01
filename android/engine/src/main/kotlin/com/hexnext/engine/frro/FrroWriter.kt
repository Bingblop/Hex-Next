package com.hexnext.engine.frro

import com.hexnext.engine.arsc.StringPoolWriter
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Builds a fabricated overlay file in the layout described by [FrroFormat].
 *
 * Intended as a fallback for devices where the `FabricatedOverlay` Java API cannot be used;
 * the primary path registers overlays through `OverlayManagerTransaction`. Version 3 colour
 * overlays are byte-identical to Samsung's One UI 9.0 files (a parsed `SemWT_android.frro`
 * rewrites to the same bytes); binary values and versions 1/2 follow the assumed layout in
 * [FrroFormat].
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
        data class Binary(val bytes: ByteArray, val ninePatch: Boolean) : Value()
    }

    // package -> type -> entry -> config -> value, insertion-ordered
    private val values = LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, LinkedHashMap<String, Value>>>>()

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

    /** Sets a file value such as a PNG or compiled 9-patch (version 3+). */
    public fun setBinary(resourceName: String, bytes: ByteArray, isNinePatch: Boolean = false, configuration: String = ""): FrroWriter {
        require(version >= 3) { "file values need FRRO version 3+" }
        return put(resourceName, configuration, Value.Binary(bytes.copyOf(), isNinePatch))
    }

    private fun put(resourceName: String, configuration: String, value: Value): FrroWriter = apply {
        val pkg = resourceName.substringBefore(':', targetPackage).ifEmpty { targetPackage }
        val typeName = resourceName.substringAfter(':')
        val type = typeName.substringBefore('/', "")
        val entry = typeName.substringAfter('/', "")
        require(type.isNotEmpty() && entry.isNotEmpty()) { "resource name must be [package:]type/name: $resourceName" }
        require(version >= 2 || configuration.isEmpty()) { "configurations need FRRO version 2+" }
        values.getOrPut(pkg) { LinkedHashMap() }.getOrPut(type) { LinkedHashMap() }
            .getOrPut(entry) { LinkedHashMap() }[configuration] = value
    }

    /** Serialises the overlay. */
    public fun toByteArray(): ByteArray {
        val strings = mutableListOf<String>()
        val binary = ByteArrayOutputStream()

        fun ProtoWriter.value(v: Value) {
            when (v) {
                is Value.Plain -> varint(1, v.dataType.toLong() and 0xFFFFFFFFL).varint(2, v.data.toLong() and 0xFFFFFFFFL)
                is Value.Text -> {
                    val index = strings.indexOf(v.value).takeIf { it >= 0 } ?: strings.size.also { strings += v.value }
                    varint(1, FrroFormat.TYPE_STRING.toLong()).varint(2, index.toLong())
                }
                is Value.Binary -> {
                    val offset = binary.size().toLong()
                    binary.write(v.bytes)
                    varint(1, FrroFormat.TYPE_STRING.toLong())
                    // offset 0 must still be present so readers can tell a file value from a string
                    forceVarint(3, offset)
                    varint(4, v.bytes.size.toLong())
                    bool(5, v.ninePatch)
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
        if (version >= 3) out.write(le32(binary.size()))
        if (version >= 2) {
            // Samsung writes the (empty) pool chunk even when there are no strings
            val pool = StringPoolWriter.write(strings)
            out.write(le32(pool.size))
            out.write(pool)
        }
        if (version >= 3) binary.writeTo(out)
        out.write(protoBytes)
        return out.toByteArray()
    }

    public companion object {
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
                        w.setBinary(e.resourceName, FrroReader.readBinary(source, e)!!, e.isNinePatch, config)
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
