package com.hexnext.engine.frro

import com.hexnext.engine.arsc.ArscFormatException
import com.hexnext.engine.arsc.LeBytes
import com.hexnext.engine.arsc.ResStringPool
import java.io.File
import java.util.zip.CRC32

/**
 * A parsed AOSP fabricated overlay (under `/data/resource-cache/`, e.g. Samsung SemWT overlays).
 *
 * @property name overlay name (the part after `packageName:` in `cmd overlay list`).
 * @property targetPackage package whose resources the overlay replaces.
 * @property targetOverlayable `<overlayable>` name the overlay targets, `""` for none.
 * @property entries one entry per (resource, configuration).
 * @property packageName owning package of the overlay.
 * @property version FRRO file version (1–3).
 * @property crc CRC stored in the header.
 * @property crcMatchesProto true when [crc] equals the CRC-32 of the protobuf payload.
 */
public data class FabricatedOverlayFile(
    val name: String,
    val targetPackage: String,
    val targetOverlayable: String,
    val entries: List<FrroEntry>,
    val packageName: String = "",
    val version: Int = FrroFormat.CURRENT_VERSION,
    val crc: Int = 0,
    val crcMatchesProto: Boolean = true,
)

/**
 * One fabricated value.
 *
 * @property resourceName `package:type/name`.
 * @property dataType `Res_value::dataType` (e.g. `0x1c` colour, `0x03` string).
 * @property data `Res_value::data`; for strings, the string-pool index.
 * @property stringData the resolved string for `TYPE_STRING` values (version 2+), else null.
 * @property binaryOffset absolute offset of the file payload inside the `.frro` (version 3), else null.
 * @property binarySize size of the file payload in bytes (version 3), else null.
 * @property isNinePatch whether the payload is a compiled 9-patch PNG.
 * @property configuration config qualifier string (`""`, `night`, `xxhdpi` …); null when the
 *   value was stored in the configuration-less `res_value` field.
 */
public data class FrroEntry(
    val resourceName: String,
    val dataType: Int,
    val data: Int,
    val stringData: String? = null,
    val binaryOffset: Int? = null,
    val binarySize: Int? = null,
    val isNinePatch: Boolean = false,
    val configuration: String? = null,
) {
    /** `type/name` part of [resourceName]. */
    public val typeAndName: String get() = resourceName.substringAfter(':')
}

/** Thrown when a `.frro` file is not a fabricated overlay or is malformed. */
public class FrroFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The fabricated overlay container layout implemented by [FrroReader] and [FrroWriter].
 *
 * **Provenance warning:** this layout was written from knowledge of AOSP
 * `frameworks/base/cmds/idmap2/libidmap2/FabricatedOverlay.cpp` and
 * `frameworks/base/cmds/idmap2/libidmap2/proto/fabricated_v1.proto` (Android 12 – 16), *not*
 * from a device file. Field numbers, the CRC coverage and the binary-offset base must be
 * verified against a real `/data/resource-cache/<overlay>.frro` (e.g. a Samsung `SemWT_*` overlay)
 * before the writer is used on a device.
 *
 * ```
 * all versions (little-endian):
 *   u32 magic    = 0x4f525246 ("FRRO")
 *   u32 version  = 1 | 2 | 3
 *   u32 crc      = CRC-32 of the protobuf bytes (assumed)
 * version 1 (Android 12/13):
 *   protobuf FabricatedOverlay … to end of file
 * version 2 (Android 14, string values):
 *   u32 proto_size, proto_size bytes protobuf
 *   u32 pool_size,  pool_size bytes ResStringPool chunk (UTF-8); TYPE_STRING data = pool index
 * version 3 (Android 14 QPR+/15/16, file values):
 *   as version 2, then
 *   u32 binary_size, binary_size bytes of concatenated file payloads;
 *   ResourceValue.data_binary_offset is relative to the start of that region
 *
 * message FabricatedOverlay { string package_name = 1; string name = 2;
 *   string target_package_name = 3; string target_overlayable = 4;
 *   repeated ResourcePackage packages = 5; }
 * message ResourcePackage   { string name = 1; repeated ResourceType types = 2; }
 * message ResourceType      { string name = 1; repeated ResourceEntry entries = 2; }
 * message ResourceEntry     { string name = 1; ResourceValue res_value = 2;
 *   map<string, ResourceValue> configs = 3; }          // key = config qualifiers
 * message ResourceValue     { uint32 data_type = 1; uint32 data_value = 2;
 *   uint64 data_binary_offset = 3; uint64 data_binary_size = 4; bool is_nine_patch = 5; }
 * ```
 */
public object FrroFormat {
    /** `kFabricatedOverlayMagic`. */
    public const val MAGIC: Int = 0x4f525246

    /** Highest version this implementation reads and the default it writes. */
    public const val CURRENT_VERSION: Int = 3

    /** `Res_value::TYPE_STRING`. */
    public const val TYPE_STRING: Int = 0x03

    /** `Res_value::TYPE_INT_COLOR_ARGB8`. */
    public const val TYPE_INT_COLOR_ARGB8: Int = 0x1c

    internal fun crc(bytes: ByteArray): Int = CRC32().apply { update(bytes) }.value.toInt()
}

/** Reads fabricated overlay files; see [FrroFormat] for the layout. */
public object FrroReader {
    /** Parses the `.frro` at [file]. */
    public fun read(file: File): FabricatedOverlayFile = read(file.readBytes())

    /** Parses a `.frro` from [bytes]. */
    public fun read(bytes: ByteArray): FabricatedOverlayFile {
        if (bytes.size < 12) throw FrroFormatException("file too small for a fabricated overlay")
        val le = LeBytes(bytes)
        if (le.i32(0) != FrroFormat.MAGIC) throw FrroFormatException("not a fabricated overlay (bad magic)")
        val version = le.i32(4)
        if (version !in 1..FrroFormat.CURRENT_VERSION) throw FrroFormatException("unsupported fabricated overlay version $version")
        val crc = le.i32(8)
        try {
            val proto: ByteArray
            var pool: ResStringPool? = null
            var binaryStart = -1
            var binaryEnd = -1
            if (version == 1) {
                proto = bytes.copyOfRange(12, bytes.size)
            } else {
                var p = 12
                val protoSize = le.i32(p); p += 4
                proto = le.slice(p, protoSize); p += protoSize
                val poolSize = le.i32(p); p += 4
                if (poolSize > 0) pool = ResStringPool.parse(LeBytes(le.slice(p, poolSize)), 0)
                p += poolSize
                if (version >= 3) {
                    val binarySize = le.i32(p); p += 4
                    le.check(p, binarySize, "binary data")
                    binaryStart = p
                    binaryEnd = p + binarySize
                }
            }
            return parseProto(proto, version, crc, pool, binaryStart, binaryEnd)
        } catch (e: ArscFormatException) {
            throw FrroFormatException("malformed fabricated overlay: ${e.message}", e)
        }
    }

    /** Returns the file payload of [entry] from the `.frro` [bytes], or null when it has none. */
    public fun readBinary(bytes: ByteArray, entry: FrroEntry): ByteArray? {
        val offset = entry.binaryOffset ?: return null
        val size = entry.binarySize ?: return null
        if (offset < 0 || size < 0 || offset.toLong() + size > bytes.size) throw FrroFormatException("binary payload out of bounds")
        return bytes.copyOfRange(offset, offset + size)
    }

    private fun parseProto(
        proto: ByteArray,
        version: Int,
        crc: Int,
        pool: ResStringPool?,
        binaryStart: Int,
        binaryEnd: Int,
    ): FabricatedOverlayFile {
        var packageName = ""
        var name = ""
        var target = ""
        var overlayable = ""
        val entries = mutableListOf<FrroEntry>()
        for (f in ProtoReader(proto).fields()) {
            when (f.number) {
                1 -> packageName = f.string()
                2 -> name = f.string()
                3 -> target = f.string()
                4 -> overlayable = f.string()
                5 -> parsePackage(f.bytes(), pool, binaryStart, binaryEnd, entries)
            }
        }
        return FabricatedOverlayFile(name, target, overlayable, entries, packageName, version, crc, FrroFormat.crc(proto) == crc)
    }

    private fun parsePackage(bytes: ByteArray, pool: ResStringPool?, bStart: Int, bEnd: Int, out: MutableList<FrroEntry>) {
        val fields = ProtoReader(bytes).fields()
        val pkg = fields.firstOrNull { it.number == 1 }?.string().orEmpty()
        for (typeField in fields.filter { it.number == 2 }) {
            val typeFields = ProtoReader(typeField.bytes()).fields()
            val type = typeFields.firstOrNull { it.number == 1 }?.string().orEmpty()
            for (entryField in typeFields.filter { it.number == 2 }) {
                val entryFields = ProtoReader(entryField.bytes()).fields()
                val entryName = entryFields.firstOrNull { it.number == 1 }?.string().orEmpty()
                val resourceName = "$pkg:$type/$entryName"
                for (ef in entryFields) {
                    when (ef.number) {
                        2 -> out += parseValue(resourceName, ef.bytes(), null, pool, bStart, bEnd)
                        3 -> {
                            val mapFields = ProtoReader(ef.bytes()).fields()
                            val config = mapFields.firstOrNull { it.number == 1 }?.string().orEmpty()
                            val value = mapFields.firstOrNull { it.number == 2 }?.bytes() ?: ByteArray(0)
                            out += parseValue(resourceName, value, config, pool, bStart, bEnd)
                        }
                    }
                }
            }
        }
    }

    private fun parseValue(
        resourceName: String,
        bytes: ByteArray,
        config: String?,
        pool: ResStringPool?,
        bStart: Int,
        bEnd: Int,
    ): FrroEntry {
        var dataType = 0
        var data = 0
        var binOffset: Long? = null
        var binSize: Long? = null
        var ninePatch = false
        for (f in ProtoReader(bytes).fields()) {
            when (f.number) {
                1 -> dataType = f.long().toInt()
                2 -> data = f.long().toInt()
                3 -> binOffset = f.long()
                4 -> binSize = f.long()
                5 -> ninePatch = f.long() != 0L
            }
        }
        var absOffset: Int? = null
        if (binOffset != null || binSize != null) {
            if (bStart < 0) throw FrroFormatException("$resourceName has binary data but the file has no binary region")
            val off = bStart + (binOffset ?: 0L)
            val size = binSize ?: 0L
            if (off + size > bEnd) throw FrroFormatException("$resourceName binary payload outside binary region")
            absOffset = off.toInt()
        }
        val string = if (dataType == FrroFormat.TYPE_STRING && absOffset == null) pool?.getOrNull(data) else null
        return FrroEntry(resourceName, dataType, data, string, absOffset, binSize?.toInt(), ninePatch, config)
    }

    private fun ProtoReader.Field.string(): String = String(bytes(), Charsets.UTF_8)

    private fun ProtoReader.Field.bytes(): ByteArray =
        (this as? ProtoReader.Field.Bytes)?.value ?: throw FrroFormatException("protobuf field $number is not length-delimited")

    private fun ProtoReader.Field.long(): Long = when (this) {
        is ProtoReader.Field.Varint -> value
        is ProtoReader.Field.Fixed -> value
        is ProtoReader.Field.Bytes -> throw FrroFormatException("protobuf field $number is not a scalar")
    }
}
