package com.hexnext.themer.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.hexnext.themer.theme.CustomAssetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Runs a generation end to end and moves results into the user's files.
 *
 * - [generate] reads the provider key from [AiKeyStore] and calls the provider on IO.
 * - [importAsCustomIcon] hands a result to [CustomAssetStore.importImage], so the icon lands in
 *   `files/custom/icons` as an overlay-ready ARGB PNG like any imported image and can be assigned
 *   to a slot.
 * - [saveToGallery] keeps a copy under `files/custom/generated` for later reuse.
 */
object IconGenerator {

    /** `files/custom/generated`. */
    fun galleryDir(context: Context): File = File(CustomAssetStore.rootDir(context), "generated").apply { mkdirs() }

    /** Generates [request] with [provider]; the key comes from [keys]. Throws [AiProviderException]. */
    suspend fun generate(keys: AiKeyStore, provider: IconProvider, request: IconRequest): List<GeneratedIcon> =
        withContext(Dispatchers.IO) {
            val apiKey = keys.get(provider.id) ?: throw AiProviderException("No key for ${provider.displayName}. Add one in Settings > AI providers.")
            provider.generate(request, apiKey)
        }

    /**
     * Stores [icon] as a custom icon through [CustomAssetStore.importImage]; the asset's display
     * name is "AI <prompt>". Blocks on file I/O; call on `Dispatchers.IO`.
     */
    fun importAsCustomIcon(context: Context, icon: GeneratedIcon): CustomAssetStore.Asset {
        val dir = File(context.cacheDir, "ai-import").apply { mkdirs() }
        val temp = File(dir, "${fileStem(icon)}.png")
        try {
            temp.writeBytes(icon.png)
            return CustomAssetStore(context).importImage(Uri.fromFile(temp), CustomAssetStore.Kind.ICON)
        } finally {
            temp.delete()
        }
    }

    /** Writes [icon] to the gallery as `<stem>-<sha256 8>.png` and returns the file. */
    fun saveToGallery(context: Context, icon: GeneratedIcon): File {
        val target = File(galleryDir(context), "${fileStem(icon)}-${sha256Hex8(icon.png)}.png")
        if (target.isFile) return target
        val tmp = File(target.parentFile, "${target.name}.part")
        try {
            tmp.writeBytes(icon.png)
            if (!tmp.renameTo(target)) {
                target.writeBytes(icon.png)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            throw AiProviderException("Saving to the gallery failed: ${e.message}", e)
        }
        return target
    }

    /** Gallery files, newest first. */
    fun listGallery(context: Context): List<File> =
        galleryDir(context).listFiles { f -> f.isFile && f.name.endsWith(".png") }.orEmpty().sortedByDescending { it.lastModified() }

    /** `AI <first 40 chars of the prompt>` as a file-safe stem. */
    private fun fileStem(icon: GeneratedIcon): String {
        val slug = icon.prompt.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).trim('-')
        return if (slug.isEmpty()) "ai-icon" else "ai-$slug"
    }

    private fun sha256Hex8(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).take(4).joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}

/**
 * Decodes any image the platform can read (PNG, JPEG, WebP) and re-encodes it as an ARGB_8888
 * PNG, scaled down so neither side exceeds [maxPx]. Throws [AiProviderException] when the bytes
 * are not an image.
 */
internal fun normalizePng(bytes: ByteArray, maxPx: Int): ByteArray {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AiProviderException("The provider sent data that is not an image.")
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
    val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inSampleSize = sample
        inScaled = false
    }
    val decoded = BitmapFactory.decodeStream(ByteArrayInputStream(bytes), null, options)
        ?: throw AiProviderException("The provider sent an image that could not be decoded.")
    var bitmap = decoded
    try {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest > maxPx) {
            val scale = maxPx.toFloat() / longest
            val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1), (bitmap.height * scale).toInt().coerceAtLeast(1), true)
            if (scaled !== bitmap) {
                bitmap.recycle()
                bitmap = scaled
            }
        }
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            val argb = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: throw AiProviderException("The image could not be converted to ARGB.")
            bitmap.recycle()
            bitmap = argb
        }
        val out = ByteArrayOutputStream(bitmap.byteCount / 4 + 1024)
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw AiProviderException("The image could not be encoded as PNG.")
        return out.toByteArray()
    } finally {
        bitmap.recycle()
    }
}
