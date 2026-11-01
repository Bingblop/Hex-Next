package com.hexnext.engine.compile

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Thrown when a `.hexnext` file is not a valid theme export. */
public class ThemeFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The contents of a `.hexnext` file. */
public data class ThemeBundle(
    val spec: ThemeSpec,
    val packIds: List<String>,
)

/**
 * `.hexnext` theme files: a zip holding [THEME_JSON] (the [ThemeSpec]) and optionally
 * [PACKS_JSON] (a JSON array of the pack ids the theme needs, so the importer can download them).
 * Entries carry a fixed timestamp, so equal themes export to equal bytes.
 */
public object ThemeExport {
    /** File extension, without the dot. */
    public const val EXTENSION: String = "hexnext"

    /** Zip entry holding the theme. */
    public const val THEME_JSON: String = "theme.json"

    /** Zip entry holding the referenced pack ids. */
    public const val PACKS_JSON: String = "packs.json"

    /** Largest entry accepted on import. */
    public const val MAX_ENTRY_BYTES: Int = 1 shl 20

    private val packListSerializer = ListSerializer(String.serializer())

    /** Exports [spec]; with [includePackIds] the theme's [ThemeSpec.referencedPackIds] are listed too. */
    public fun export(spec: ThemeSpec, includePackIds: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name).apply { time = FIXED_TIME })
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put(THEME_JSON, spec.toJson())
            if (includePackIds) put(PACKS_JSON, SpecJson.encodeToString(packListSerializer, spec.referencedPackIds))
        }
        return out.toByteArray()
    }

    /** Reads the theme from a `.hexnext` file. */
    public fun import(bytes: ByteArray): ThemeSpec = read(bytes).spec

    /** Reads the theme and its pack id list (empty when the file has none). */
    public fun read(bytes: ByteArray): ThemeBundle {
        val entries = HashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory || entry.name !in setOf(THEME_JSON, PACKS_JSON)) continue
                    entries[entry.name] = readLimited(zip, entry.name)
                }
            }
        } catch (e: ZipException) {
            throw ThemeFormatException("not a .hexnext file: ${e.message}", e)
        } catch (e: IOException) {
            throw ThemeFormatException("cannot read .hexnext file: ${e.message}", e)
        }
        val theme = entries[THEME_JSON] ?: throw ThemeFormatException("$THEME_JSON missing")
        val spec = try {
            ThemeSpec.fromJson(String(theme, Charsets.UTF_8))
        } catch (e: IllegalArgumentException) {
            throw ThemeFormatException("invalid $THEME_JSON: ${e.message}", e)
        }
        val packIds = entries[PACKS_JSON]?.let {
            try {
                SpecJson.decodeFromString(packListSerializer, String(it, Charsets.UTF_8))
            } catch (e: SerializationException) {
                throw ThemeFormatException("invalid $PACKS_JSON: ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                throw ThemeFormatException("invalid $PACKS_JSON: ${e.message}", e)
            }
        }.orEmpty()
        return ThemeBundle(spec, packIds)
    }

    private fun readLimited(zip: ZipInputStream, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_ENTRY_BYTES) throw ThemeFormatException("$name is larger than $MAX_ENTRY_BYTES bytes")
        }
        return out.toByteArray()
    }

    /** Fixed entry time (2020-01-01T00:00:00Z) so exports are reproducible. */
    private const val FIXED_TIME: Long = 1577836800000L
}
