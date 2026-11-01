package com.hexnext.themer.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.hexnext.engine.font.FontFile
import com.hexnext.engine.font.FontFileException
import com.hexnext.engine.frro.IcoException
import com.hexnext.engine.frro.IcoReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The user's imported assets under `files/custom/<icons|backgrounds|fonts>`.
 *
 * Images (PNG, JPG, WebP, GIF, BMP, ICO) are decoded and re-encoded as plain ARGB_8888 PNGs so
 * that every stored icon or background is overlay-ready (no EXIF, no ICC profile, no palette).
 * Fonts are validated as sfnt files (TrueType, OpenType CFF, Apple `true`) and copied as is.
 * Stored names are `<sanitized original stem>-<first 8 hex of sha256>.<png|ttf|otf>`; the
 * original display name is kept in a `<file>.name` sidecar.
 *
 * Every function blocks on file I/O; call them on `Dispatchers.IO`. [importImage] and
 * [importFont] throw [IOException] with a message fit for the screen.
 */
class CustomAssetStore(context: Context) {

    private val appContext = context.applicationContext

    enum class Kind(val dir: String) { ICON("icons"), BACKGROUND("backgrounds"), FONT("fonts") }

    /**
     * One stored asset. [width] and [height] are 0 for fonts; [displayName] is the original file
     * stem for images and `family [subfamily]` for fonts.
     */
    data class Asset(
        val kind: Kind,
        val fileName: String,
        val displayName: String,
        val width: Int,
        val height: Int,
        val bytes: Long,
    )

    /** Every asset of [kind], sorted by display name. */
    fun list(kind: Kind): List<Asset> {
        val files = dir(kind).listFiles { f -> f.isFile && hasAssetExtension(kind, f.name) }.orEmpty()
        return files.mapNotNull { file -> runCatching { describe(kind, file) }.getOrNull() }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })
    }

    /** The file of a stored asset (it may not exist any more). */
    fun file(kind: Kind, fileName: String): File {
        requireSafeName(fileName)
        return File(dir(kind), fileName)
    }

    /** Deletes [asset] and its sidecar; a missing file is not an error. */
    fun delete(asset: Asset) {
        val file = file(asset.kind, asset.fileName)
        file.delete()
        sidecar(file).delete()
    }

    /**
     * Imports the image document at [uri] as an ARGB PNG of [kind]. Accepts PNG, JPG, WebP, GIF
     * and BMP through [BitmapFactory] and `.ico` files (by extension or magic) through
     * [IcoReader], taking the largest, deepest entry. Rejects images over 4096×4096 or 25 MB.
     */
    fun importImage(uri: Uri, kind: Kind): Asset {
        require(kind != Kind.FONT) { "importImage cannot store a font" }
        val sourceName = displayNameOf(uri)
        val bytes = readAll(uri, MAX_IMAGE_BYTES, "image")
        val bitmap = if (isIco(sourceName, bytes)) decodeIco(bytes) else decodeImage(bytes)
        try {
            val argb = if (bitmap.config == Bitmap.Config.ARGB_8888) {
                bitmap
            } else {
                bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    ?: throw IOException("The image could not be converted to ARGB.")
            }
            val png = ByteArrayOutputStream(argb.byteCount / 4 + 1024).also { out ->
                if (!argb.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("The image could not be encoded as PNG.")
            }.toByteArray()
            val fileName = "${sanitizeStem(sourceName)}-${sha256Hex8(png)}.png"
            val file = store(kind, fileName, png, stemOf(sourceName))
            val asset = Asset(kind, file.name, stemOf(sourceName), argb.width, argb.height, file.length())
            if (argb !== bitmap) argb.recycle()
            return asset
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Imports the font document at [uri]. Validates the sfnt header and the `name` table
     * ([FontFile.info]) and copies the bytes as `.otf` (CFF outlines) or `.ttf`.
     */
    fun importFont(uri: Uri): Asset {
        val sourceName = displayNameOf(uri)
        val bytes = readAll(uri, MAX_FONT_BYTES, "font")
        val info = try {
            FontFile.info(bytes)
        } catch (e: FontFileException) {
            throw IOException("Not a TrueType or OpenType font: ${e.message}", e)
        }
        val family = info.family.trim().ifEmpty { stemOf(sourceName) }
        val subfamily = info.subfamily.trim()
        val displayName = if (subfamily.isEmpty() || subfamily.equals("Regular", ignoreCase = true)) family else "$family $subfamily"
        val extension = if (info.isOpenTypeCff) "otf" else "ttf"
        val fileName = "${sanitizeStem(sourceName)}-${sha256Hex8(bytes)}.$extension"
        val file = store(Kind.FONT, fileName, bytes, displayName)
        return Asset(Kind.FONT, file.name, displayName, 0, 0, file.length())
    }

    // ---- decoding -------------------------------------------------------------------------

    private fun decodeImage(bytes: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Not a supported image. Use PNG, JPG, WebP, GIF, BMP or ICO.")
        }
        checkDimensions(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inMutable = false
        }
        return BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, options)
            ?: throw IOException("The image could not be decoded.")
    }

    private fun decodeIco(bytes: ByteArray): Bitmap {
        val entry = try {
            IcoReader.best(bytes)
        } catch (e: IcoException) {
            throw IOException("Not a valid ICO file: ${e.message}", e)
        }
        return when (entry) {
            is IcoReader.Entry.Png -> decodeImage(entry.png)
            is IcoReader.Entry.Dib -> {
                val image = entry.image
                checkDimensions(image.width, image.height)
                Bitmap.createBitmap(image.argb, image.width, image.height, Bitmap.Config.ARGB_8888)
            }
        }
    }

    private fun checkDimensions(width: Int, height: Int) {
        if (width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw IOException("The image is ${width}×${height}; the limit is ${MAX_DIMENSION}×${MAX_DIMENSION}.")
        }
    }

    // ---- files ----------------------------------------------------------------------------

    private fun readAll(uri: Uri, limit: Long, what: String): ByteArray {
        val input = try {
            appContext.contentResolver.openInputStream(uri)
        } catch (e: Exception) {
            throw IOException("The selected $what could not be opened: ${e.message}", e)
        } ?: throw IOException("The selected $what could not be opened.")
        val out = ByteArrayOutputStream()
        input.use { source ->
            val chunk = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = source.read(chunk)
                if (n < 0) break
                total += n
                if (total > limit) throw IOException("The $what is larger than ${limit / MIB} MB.")
                out.write(chunk, 0, n)
            }
        }
        if (out.size() == 0) throw IOException("The selected $what is empty.")
        return out.toByteArray()
    }

    /** Writes [bytes] to `<kind dir>/<fileName>` through a temp file, plus the display-name sidecar. */
    private fun store(kind: Kind, fileName: String, bytes: ByteArray, displayName: String): File {
        requireSafeName(fileName)
        val target = File(dir(kind), fileName)
        val tmp = File(target.parentFile, "$fileName.part")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.writeBytes(bytes)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            throw IOException("Saving the file failed: ${e.message}", e)
        }
        runCatching { sidecar(target).writeText(displayName) }
        return target
    }

    private fun describe(kind: Kind, file: File): Asset {
        val displayName = runCatching { sidecar(file).takeIf { it.isFile }?.readText()?.trim() }.getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?: file.nameWithoutExtension.replace(HASH_SUFFIX, "")
        var width = 0
        var height = 0
        if (kind != Kind.FONT) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            width = bounds.outWidth.coerceAtLeast(0)
            height = bounds.outHeight.coerceAtLeast(0)
        }
        return Asset(kind, file.name, displayName, width, height, file.length())
    }

    private fun dir(kind: Kind): File = File(rootDir(appContext), kind.dir).apply { mkdirs() }

    private fun sidecar(file: File): File = File(file.parentFile, "${file.name}.name")

    private fun displayNameOf(uri: Uri): String {
        val fromProvider = runCatching {
            appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
            }
        }.getOrNull()
        return fromProvider?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "asset"
    }

    companion object {
        private const val MIB = 1024L * 1024L
        private const val MAX_IMAGE_BYTES = 25L * MIB
        private const val MAX_FONT_BYTES = 64L * MIB
        private const val MAX_DIMENSION = 4096
        private const val MAX_STEM = 40
        private val SAFE_NAME = Regex("^[A-Za-z0-9_.-]+$")
        private val HASH_SUFFIX = Regex("-[0-9a-f]{8}$")

        /** `files/custom`: the root the renderer reads custom assets from (`<root>/<kind dir>/<file>`). */
        fun rootDir(context: Context): File = File(context.applicationContext.filesDir, "custom").apply { mkdirs() }

        /** The original name without extension, for display. */
        internal fun stemOf(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
            val stem = if (base.lastIndexOf('.') > 0) base.substringBeforeLast('.') else base
            return stem.trim().ifEmpty { "asset" }
        }

        /** Lower-case `[a-z0-9-_]` version of the stem, runs of other characters folded to `-`, at most 40 characters. */
        internal fun sanitizeStem(name: String): String {
            val folded = stemOf(name).lowercase().replace(Regex("[^a-z0-9_-]+"), "-").trim('-')
            return folded.take(MAX_STEM).trim('-').ifEmpty { "asset" }
        }

        internal fun isIco(name: String, bytes: ByteArray): Boolean {
            if (name.lowercase().endsWith(".ico") || name.lowercase().endsWith(".cur")) return true
            if (bytes.size < 6) return false
            val type = (bytes[2].toInt() and 0xFF) or ((bytes[3].toInt() and 0xFF) shl 8)
            return bytes[0] == 0.toByte() && bytes[1] == 0.toByte() && (type == 1 || type == 2)
        }

        private fun hasAssetExtension(kind: Kind, name: String): Boolean {
            val lower = name.lowercase()
            return when (kind) {
                Kind.FONT -> lower.endsWith(".ttf") || lower.endsWith(".otf")
                else -> lower.endsWith(".png")
            }
        }

        private fun requireSafeName(fileName: String) {
            require(SAFE_NAME.matches(fileName) && !fileName.startsWith(".")) { "Invalid asset file name \"$fileName\"." }
        }

        private fun sha256Hex8(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        }
    }
}
