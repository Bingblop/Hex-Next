package com.hexnext.themer.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import com.hexnext.engine.compile.PlannedDrawableJob
import com.hexnext.engine.pack.DirectoryPackResolver
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.util.zip.ZipFile

/**
 * Composites the layers of a [PlannedDrawableJob] into one PNG with `android.graphics`.
 *
 * Layers are drawn bottom first onto a canvas the size of the first layer; later layers are
 * scaled to it. A tinted layer is drawn through a `MULTIPLY` colour filter, so the greyscale
 * shading of the pack PNG survives and the tint's alpha scales the layer's alpha.
 *
 * Nine-patches: FabricatedOverlay compiles 9-patches itself from a source PNG with the 1 px
 * marker border, so the composite keeps the border of the first `.9.png` layer untinted. A layer
 * that was already compiled (its PNG carries an `npTc` chunk, so decoding strips the border)
 * cannot be rebuilt here; such a job is rendered as a plain PNG and [Rendered.warning] says so.
 * A single untinted layer is passed through byte for byte.
 */
class DrawableRenderer(private val packs: DirectoryPackResolver) : Closeable {

    /** [png] to fabricate; [ninePatch] whether to fabricate it as a 9-patch. */
    class Rendered(val png: ByteArray, val ninePatch: Boolean, val warning: String? = null)

    private val zips = HashMap<String, ZipFile>()

    fun render(job: PlannedDrawableJob): Rendered {
        val steps = job.plan.steps
        if (steps.isEmpty()) throw IOException("no layers for ${job.resourceName}")
        val zip = zip(job.source.packId)
        if (steps.size == 1 && steps[0].tintArgb == null) {
            return Rendered(read(zip, steps[0].layerPath), job.ninePatch)
        }

        val decoded = ArrayList<Bitmap>(steps.size)
        try {
            for (step in steps) decoded.add(decode(read(zip, step.layerPath), step.layerPath))
            val base = decoded[0]
            val width = base.width
            val height = base.height
            val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            try {
                val canvas = Canvas(out)
                val dst = Rect(0, 0, width, height)
                steps.forEachIndexed { i, step ->
                    val paint = Paint(Paint.FILTER_BITMAP_FLAG)
                    val tint = step.tintArgb
                    if (tint != null) paint.colorFilter = PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY)
                    canvas.drawBitmap(decoded[i], null, dst, paint)
                }

                var ninePatch = job.ninePatch
                var warning: String? = null
                if (ninePatch) {
                    val index = steps.indexOfFirst { it.isNinePatch }
                    val border = decoded.getOrNull(index)
                    when {
                        border == null -> {
                            ninePatch = false
                            warning = "no 9-patch layer; applied as a plain PNG"
                        }
                        border.ninePatchChunk != null -> {
                            ninePatch = false
                            warning = "${steps[index].layerPath} is a compiled 9-patch; applied as a plain PNG"
                        }
                        border.width != width || border.height != height || width < 3 || height < 3 -> {
                            ninePatch = false
                            warning = "9-patch layer size ${border.width}x${border.height} differs from ${width}x$height; applied as a plain PNG"
                        }
                        else -> copyBorder(border, out)
                    }
                }
                return Rendered(encode(out), ninePatch, warning)
            } finally {
                out.recycle()
            }
        } finally {
            decoded.forEach { it.recycle() }
        }
    }

    override fun close() {
        zips.values.forEach { runCatching { it.close() } }
        zips.clear()
    }

    private fun zip(packId: String): ZipFile {
        zips[packId]?.let { return it }
        val file = packs.zipFile(packId) ?: throw IOException("pack $packId is not installed")
        return ZipFile(file).also { zips[packId] = it }
    }

    private fun read(zip: ZipFile, path: String): ByteArray {
        val entry = zip.getEntry(path) ?: throw IOException("$path not found in ${zip.name.substringAfterLast('/')}")
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    private fun decode(bytes: ByteArray, path: String): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("cannot decode $path")
    }

    /** Copies the 1 px marker border of [source] (untinted) over [target]. */
    private fun copyBorder(source: Bitmap, target: Bitmap) {
        val w = source.width
        val h = source.height
        val row = IntArray(w)
        for (y in intArrayOf(0, h - 1)) {
            source.getPixels(row, 0, w, 0, y, w, 1)
            target.setPixels(row, 0, w, 0, y, w, 1)
        }
        val column = IntArray(h)
        for (x in intArrayOf(0, w - 1)) {
            source.getPixels(column, 0, 1, x, 0, 1, h)
            target.setPixels(column, 0, 1, x, 0, 1, h)
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) throw IOException("PNG encoding failed")
        return out.toByteArray()
    }
}
