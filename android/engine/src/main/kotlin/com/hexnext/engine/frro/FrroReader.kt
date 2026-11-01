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
 * @property crcMatchesProto true when [crc] equals CRC-32 of (version bytes + protobuf), the
 *   coverage verified on a One UI 9.0 file. Informational only: a mismatch never fails parsing.
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
 *   Computed as start of the binary region + `data_binary_offset` (assumed, see [FrroFormat]).
 * @property binarySize size of the file payload in bytes (version 3), else null.
 * @property isNinePatch whether the payload is a compiled 9-patch PNG.
 * @property configuration config qualifier string (`night`, `xxhdpi` …) from `ResourceEntry`
 *   field 3; null for the default configuration (field absent).
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
 * **Provenance.** Version 3 is verified byte-for-byte against a real Samsung overlay pulled from
 * a Galaxy SM-S948U1 on One UI 9.0 / Android 17 (SDK 37):
 * `/data/resource-cache/android-SemWT_android-dX00.frro`, kept as
 * `docs/device/oneui9-sm-s948u1/SemWT_android.frro` and the test fixture
 * `semwt_android_oneui9.frro`. [FrroWriter] reproduces that file exactly from its parsed form.
 * The sample holds colours only, so everything about binary (file) values is an assumption,
 * as are versions 1 and 2 (inferred from the version 3 layout, not seen on a device).
 *
 * ```
 * header, all versions (little-endian)                                         [verified v3]
 *   u32 magic    = 0x4f525246 ("FRRO")
 *   u32 version  = 1 | 2 | 3
 *   u32 crc      = CRC-32 (zlib) of the 4 version bytes followed by the protobuf bytes
 *                  (not the string pool, not the binary region)                 [verified v3]
 * version 3 (seen on One UI 9.0)
 *   u32 total_binary_bytes                                (0 in the sample)     [verified]
 *   u32 string_pool_size                                  (28 in the sample)    [verified]
 *   string_pool_size bytes: ResStringPool chunk, UTF-8; written even when empty
 *     (an empty pool is the 28-byte header 01 00 1c 00 1c 00 00 00 ... flags 0x100,
 *     stringsStart 0x1c); TYPE_STRING data_value = index into it          [verified empty]
 *   total_binary_bytes bytes: concatenated file payloads                       [ASSUMED]
 *   protobuf FabricatedOverlay to end of file                                  [verified]
 *   ResourceValue.data_binary_offset is relative to the start of the binary region
 *   (offset 20 + string_pool_size), as AOSP FabricatedOverlay.cpp appears to write it [ASSUMED]
 * version 2: header, u32 string_pool_size, pool, protobuf to EOF                [ASSUMED]
 * version 1: header, protobuf to EOF                                            [ASSUMED]
 *
 * message FabricatedOverlay {
 *   repeated ResourcePackage packages = 1;                                      [verified]
 *   string name = 2;                          // "SemWT_android"                [verified]
 *   string package_name = 3;                  // owner, "android" for SemWT     [see below]
 *   string target_package_name = 4;           // "android"                      [see below]
 *   string target_overlayable = 5;            // absent in SemWT files          [ASSUMED]
 * }
 * message ResourcePackage { string name = 1; repeated ResourceType types = 2; } [verified]
 * message ResourceType    { string name = 1; repeated ResourceEntry entries = 2; } [verified]
 * message ResourceEntry {
 *   string name = 1;                                                            [verified]
 *   ResourceValue res_value = 2;                                                [verified]
 *   string configuration = 3;   // "night" …; absent for the default config     [verified]
 * }                             // one ResourceEntry per (name, configuration)
 * message ResourceValue {
 *   uint32 data_type = 1;       // 0x1c colour                                  [verified]
 *   uint32 data_value = 2;      // varint, so 0xffxxxxxx colours take 5 bytes   [verified]
 *   uint64 data_binary_offset = 3; uint64 data_binary_size = 4;                 [ASSUMED]
 *   bool is_nine_patch = 5;                                                     [ASSUMED]
 * }
 * ```
 *
 * Fields 3 and 4 of `FabricatedOverlay`: the sample has field 3 = field 4 = `"android"` and
 * no field 5. `cmd overlay dump` prints `mTargetOverlayableName` empty and `mPackageName`
 * `android` for every SemWT overlay, so field 4 cannot be the overlayable name (that would make
 * `SemWT_android`'s overlayable `"android"`) and field 5 is the only candidate left for it.
 * Fields 3/4 are therefore owner and target; their order (3 = owner `package_name`,
 * 4 = `target_package_name`) follows AOSP `fabricated_v1.proto` and cannot be told apart in
 * this sample because both are `"android"`. A `SemWT_<pkg>` file for a non-framework target
 * would settle it.
 */
public object FrroFormat {
    /** `kFabricatedOverlayMagic`. */
    public const val MAGIC: Int = 0x4f525246

    /** Highest version this implementation reads and the default it writes (One UI 9.0 uses 3). */
    public const val CURRENT_VERSION: Int = 3

    /** `Res_value::TYPE_STRING`. */
    public const val TYPE_STRING: Int = 0x03

    /** `Res_value::TYPE_INT_COLOR_ARGB8`. */
    public const val TYPE_INT_COLOR_ARGB8: Int = 0x1c

    /** Size of the fixed header (magic, version, crc). */
    internal const val HEADER_SIZE: Int = 12

    /**
     * The header CRC: CRC-32 over the little-endian version followed by [proto]
     * (verified on a One UI 9.0 version 3 file).
     */
    internal fun crc(version: Int, proto: ByteArray): Int = CRC32().apply {
        update(byteArrayOf(version.toByte(), (version shr 8).toByte(), (version shr 16).toByte(), (version shr 24).toByte()))
        update(proto)
    }.value.toInt()
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
            var p = FrroFormat.HEADER_SIZE
            var pool: ResStringPool? = null
            var binaryStart = -1
            var binaryEnd = -1
            var binarySize = 0
            if (version >= 3) {
                binarySize = le.i32(p); p += 4
                if (binarySize < 0) throw FrroFormatException("negative binary size")
            }
            if (version >= 2) {
                val poolSize = le.i32(p); p += 4
                if (poolSize < 0) throw FrroFormatException("negative string pool size")
                le.check(p, poolSize, "string pool")
                if (poolSize > 0) pool = ResStringPool.parse(LeBytes(le.slice(p, poolSize)), 0)
                p += poolSize
            }
            if (version >= 3) {
                le.check(p, binarySize, "binary data")
                binaryStart = p
                binaryEnd = p + binarySize
                p = binaryEnd
            }
            val proto = bytes.copyOfRange(p, bytes.size)
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
                1 -> parsePackage(f.bytes(), pool, binaryStart, binaryEnd, entries)
                2 -> name = f.string()
                3 -> packageName = f.string()
                4 -> target = f.string()
                5 -> overlayable = f.string()
            }
        }
        val crcOk = FrroFormat.crc(version, proto) == crc
        return FabricatedOverlayFile(name, target, overlayable, entries, packageName, version, crc, crcOk)
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
                val config = entryFields.firstOrNull { it.number == 3 }?.string()
                val value = entryFields.firstOrNull { it.number == 2 }?.bytes() ?: ByteArray(0)
                out += parseValue("$pkg:$type/$entryName", value, config, pool, bStart, bEnd)
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
