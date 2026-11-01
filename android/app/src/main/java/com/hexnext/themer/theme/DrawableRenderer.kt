package com.hexnext.themer.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import com.hexnext.engine.compile.PlannedDrawableJob
import com.hexnext.engine.frro.NinePatch
import com.hexnext.engine.frro.NinePatchException
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
 * Nine-patches are compiled here, the way aapt2 does at build time: the composite keeps the
 * 1 px marker border of the first `.9.png` layer untinted, the border is read into a
 * [NinePatch.Border], the inner image is encoded as a PNG and [NinePatch.embed] adds the `npTc`
 * (and `npLb`) chunk. The result is shipped as a PLAIN PNG ([Rendered.ninePatch] is false):
 * Android's `BitmapFactory` turns any PNG with an `npTc` chunk into a `NinePatch` when the
 * resource is loaded. A single untinted `.9.png` layer is compiled the same way, unless its PNG
 * already carries an `npTc` chunk, in which case the bytes are passed through unchanged.
 *
 * When the marker border is malformed ([NinePatchException]) the INNER image is shipped as a
 * plain PNG, so marker pixels never reach the device, and [Rendered.warning] carries the reason.
 * A composite whose 9-patch layer was already compiled (decoding stripped its border) or whose
 * size differs from the canvas is rendered as a plain PNG with a warning as well.
 */
class DrawableRenderer(private val packs: DirectoryPackResolver) : Closeable {

    /**
     * [png] to fabricate. [ninePatch] asks the service to fabricate it as a 9-patch SOURCE
     * (marker border still present); since compilation happens here it is always false, both
     * for a compiled 9-patch (the PNG carries `npTc`) and for the plain-PNG fallbacks, whose
     * reason is in [warning]. The field stays so callers keep compiling.
     */
    class Rendered(val png: ByteArray, val ninePatch: Boolean, val warning: String? = null)

    private val zips = HashMap<String, ZipFile>()

    fun render(job: PlannedDrawableJob): Rendered {
        val steps = job.plan.steps
        if (steps.isEmpty()) throw IOException("no layers for ${job.resourceName}")
        val zip = zip(job.source.packId)
        if (steps.size == 1 && steps[0].tintArgb == null) {
            val bytes = read(zip, steps[0].layerPath)
            if (!job.ninePatch) return Rendered(bytes, ninePatch = false)
            return compileSingle(bytes, steps[0].layerPath)
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

                var warning: String? = null
                if (job.ninePatch) {
                    val index = steps.indexOfFirst { it.isNinePatch }
                    val border = decoded.getOrNull(index)
                    when {
                        border == null -> {
                            warning = "no 9-patch layer; applied as a plain PNG"
                        }
                        border.ninePatchChunk != null -> {
                            warning = "${steps[index].layerPath} is a compiled 9-patch; applied as a plain PNG"
                        }
                        border.width != width || border.height != height || width < 3 || height < 3 -> {
                            warning = "9-patch layer size ${border.width}x${border.height} differs from ${width}x$height; applied as a plain PNG"
                        }
                        else -> {
                            copyBorder(border, out)
                            return compileNinePatch(out, steps[index].layerPath)
                        }
                    }
                }
                return Rendered(encode(out), ninePatch = false, warning = warning)
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

    /**
     * A single untinted `.9.png` layer: an already compiled PNG (it carries `npTc`, so the decoder
     * reports a [Bitmap.getNinePatchChunk]) is passed through byte for byte; a source with the
     * marker border is compiled.
     */
    private fun compileSingle(bytes: ByteArray, path: String): Rendered {
        val source = decode(bytes, path)
        try {
            if (source.ninePatchChunk != null) return Rendered(bytes, ninePatch = false)
            if (source.width < 3 || source.height < 3) {
                return Rendered(bytes, ninePatch = false, warning = "$path is ${source.width}x${source.height}, too small for a 9-patch marker border; applied as a plain PNG")
            }
            return compileNinePatch(source, path)
        } finally {
            source.recycle()
        }
    }

    /**
     * Compiles [source] (a bitmap of at least 3x3 whose outer 1 px ring is the marker border)
     * into a plain PNG of the inner image carrying the `npTc`/`npLb` chunks. On a malformed
     * border the inner PNG is returned without chunks and the reason goes into the warning.
     */
    private fun compileNinePatch(source: Bitmap, label: String): Rendered {
        val w = source.width
        val h = source.height
        val innerWidth = w - 2
        val innerHeight = h - 2
        val inner = Bitmap.createBitmap(source, 1, 1, innerWidth, innerHeight)
        try {
            val innerPng = encode(inner)
            return try {
                val compiled = NinePatch.compile(readBorder(source), innerWidth, innerHeight) { x, y -> inner.getPixel(x, y) }
                Rendered(NinePatch.embed(innerPng, compiled), ninePatch = false)
            } catch (e: NinePatchException) {
                Rendered(innerPng, ninePatch = false, warning = "$label: ${e.message}; applied as a plain PNG without the marker border")
            }
        } finally {
            if (inner !== source) inner.recycle()
        }
    }

    /** Reads the four marker edges of [source] without the corner pixels, as [NinePatch.Border]. */
    private fun readBorder(source: Bitmap): NinePatch.Border {
        val w = source.width
        val h = source.height
        val row = IntArray(w)
        source.getPixels(row, 0, w, 0, 0, w, 1)
        val top = row.copyOfRange(1, w - 1)
        source.getPixels(row, 0, w, 0, h - 1, w, 1)
        val bottom = row.copyOfRange(1, w - 1)
        val column = IntArray(h)
        source.getPixels(column, 0, 1, 0, 0, 1, h)
        val left = column.copyOfRange(1, h - 1)
        source.getPixels(column, 0, 1, w - 1, 0, 1, h)
        val right = column.copyOfRange(1, h - 1)
        return NinePatch.Border(top, left, bottom, right)
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
