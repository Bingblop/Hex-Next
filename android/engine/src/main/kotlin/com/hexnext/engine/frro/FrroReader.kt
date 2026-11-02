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
 * @property data `Res_value::data`; for strings and file values, the string-pool index.
 * @property stringData the resolved pool string for `TYPE_STRING` values (version 2+), else null.
 *   For a file value this is its `frro:/<path>?offset=&size=` URI.
 * @property binaryOffset absolute file offset of the payload of a file value (version 3), parsed
 *   from the `offset=` of its URI, else null.
 * @property binarySize payload size in bytes of a file value (version 3), from `size=`, else null.
 * @property isNinePatch always false when read from disk: idmap2d compiles nine-patches into a
 *   plain PNG with an `npTc` chunk and records no flag. Kept for API compatibility.
 * @property configuration config qualifier string (`night`, `xxhdpi` …) from `ResourceEntry`
 *   field 3; null for the default configuration (field absent).
 * @property binaryPath the `.frro` path named by a file value's URI, else null.
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
    val binaryPath: String? = null,
) {
    /** `type/name` part of [resourceName]. */
    public val typeAndName: String get() = resourceName.substringAfter(':')
}

/** Thrown when a `.frro` file is not a fabricated overlay or is malformed. */
public class FrroFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The fabricated overlay container layout implemented by [FrroReader] and [FrroWriter].
 *
 * **Provenance.** The layout is verified from AOSP
 * `frameworks/base/cmds/idmap2/libidmap2/FabricatedOverlay.cpp` (`ToBinaryStream`,
 * `FromBinaryStream`) and `fabricated_v1.proto`; see `docs/reference/frro-binary-format.md` for
 * the full write-up. Version 3 colour overlays are also verified byte-for-byte against a real
 * Samsung overlay pulled from a Galaxy SM-S948U1 on One UI 9.0 / Android 17 (SDK 37):
 * `/data/resource-cache/android-SemWT_android-dX00.frro`, kept as
 * `docs/device/oneui9-sm-s948u1/SemWT_android.frro` and the test fixture
 * `semwt_android_oneui9.frro`. [FrroWriter] reproduces that file exactly from its parsed form.
 *
 * ```
 * header (little-endian)
 *   u32 magic    = 0x4f525246 ("FRRO")
 *   u32 version  = 1 | 2 | 3
 *   u32 crc      = CRC-32 (zlib) of the 4 version bytes followed by the protobuf bytes
 *                  (not the string pool, not the binary payloads)
 * version 3
 *   u32 total_binary_bytes
 *   total_binary_bytes bytes: concatenated file payloads in entry order; the first payload
 *     starts at file offset 16 (FRRO_HEADER_SIZE)
 *   u32 string_pool_size
 *   string_pool_size bytes: ResStringPool chunk, UTF-8 (android::StringPool::FlattenUtf8);
 *     written even when empty (28-byte header, flags 0x100, stringsStart 0x1c)
 *   protobuf FabricatedOverlay to end of file (deterministic serialisation)
 * version 2: header, u32 string_pool_size, pool, protobuf to EOF
 * version 1: header, protobuf to EOF
 *
 * message FabricatedOverlay {
 *   repeated ResourcePackage packages = 1;
 *   string name = 2;                          // "SemWT_android"
 *   string package_name = 3;                  // owner, "android" for SemWT
 *   string target_package_name = 4;           // "android"
 *   string target_overlayable = 5;            // absent (empty) in SemWT files
 * }
 * message ResourcePackage { string name = 1; repeated ResourceType types = 2; }
 * message ResourceType    { string name = 1; repeated ResourceEntry entries = 2; }
 * message ResourceEntry {
 *   string name = 1;
 *   ResourceValue res_value = 2;
 *   string configuration = 3;   // "night" …; absent for the default config
 * }                             // one ResourceEntry per (name, configuration)
 * message ResourceValue {
 *   uint32 data_type = 1;       // 0x1c colour, 0x03 string / file
 *   uint32 data_value = 2;      // varint; colour value or string-pool index
 * }
 * ```
 *
 * Packages, types and entries are written from `std::map`s keyed by name, configurations
 * sorted within an entry.
 *
 * A file (binary) value is a `TYPE_STRING` whose pool string points back into the same file:
 * `frro:/<absolute frro path>?offset=<16 + bytes of earlier payloads>&size=<payload bytes>`.
 * The target app's `ResourcesImpl` opens that byte range as an asset file descriptor. The URI
 * embeds the final path, so a hand-written file must be written to exactly that path.
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

    /** Prefix of a file value's pool string; the absolute path follows directly. */
    public const val URI_SCHEME: String = "frro:/"

    /** Size of the fixed header (magic, version, crc). */
    internal const val HEADER_SIZE: Int = 12

    /** `FRRO_HEADER_SIZE`: version 3 header including total_binary_bytes; first payload offset. */
    public const val V3_HEADER_SIZE: Int = 16

    private val URI = Regex("""^frro:/(/[^?]+)\?offset=(\d+)&size=(\d+)$""")

    /** Parses a file value's `frro:/<path>?offset=&size=` URI into (path, offset, size), or null. */
    public fun parseBinaryUri(uri: String): Triple<String, Long, Long>? {
        val m = URI.matchEntire(uri) ?: return null
        val offset = m.groupValues[2].toLongOrNull() ?: return null
        val size = m.groupValues[3].toLongOrNull() ?: return null
        return Triple(m.groupValues[1], offset, size)
    }

    /**
     * The header CRC: CRC-32 over the little-endian version followed by [proto]
     * (AOSP `FabricatedOverlay`, verified on a One UI 9.0 version 3 file).
     */
    public fun crc(version: Int, proto: ByteArray): Int = CRC32().apply {
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
            if (version >= 3) {
                val binarySize = le.i32(p); p += 4
                if (binarySize < 0) throw FrroFormatException("negative binary size")
                le.check(p, binarySize, "binary data")
                binaryStart = p
                binaryEnd = p + binarySize
                p = binaryEnd
            }
            if (version >= 2) {
                val poolSize = le.i32(p); p += 4
                if (poolSize < 0) throw FrroFormatException("negative string pool size")
                le.check(p, poolSize, "string pool")
                if (poolSize > 0) pool = ResStringPool.parse(LeBytes(le.slice(p, poolSize)), 0)
                p += poolSize
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
        for (f in ProtoReader(bytes).fields()) {
            when (f.number) {
                1 -> dataType = f.long().toInt()
                2 -> data = f.long().toInt()
            }
        }
        val string = if (dataType == FrroFormat.TYPE_STRING) pool?.getOrNull(data) else null
        val uri = string?.takeIf { it.startsWith(FrroFormat.URI_SCHEME) }?.let { FrroFormat.parseBinaryUri(it) }
            ?: return FrroEntry(resourceName, dataType, data, string, configuration = config)
        val (path, offset, size) = uri
        if (bStart < 0) throw FrroFormatException("$resourceName has a file value but the file has no binary region")
        if (offset < bStart || offset + size > bEnd) {
            throw FrroFormatException("$resourceName payload [$offset, +$size) outside binary region [$bStart, $bEnd)")
        }
        return FrroEntry(resourceName, dataType, data, string, offset.toInt(), size.toInt(), false, config, path)
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
