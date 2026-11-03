package com.hexnext.themer.theme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Rect
import android.graphics.Shader
import com.hexnext.engine.compile.CustomAssets
import com.hexnext.engine.compile.IconBadge
import com.hexnext.engine.compile.IconSynthesis
import com.hexnext.engine.compile.PlannedDrawableJob
import com.hexnext.engine.pack.CompositeStep
import com.hexnext.engine.frro.NinePatch
import com.hexnext.engine.frro.NinePatchException
import com.hexnext.engine.pack.DirectoryPackResolver
import com.hexnext.engine.pack.PackReader
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.math.min
import kotlin.math.roundToInt
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Composites the layers of a [PlannedDrawableJob] into one PNG with `android.graphics`.
 *
 * Layers are drawn bottom first onto a canvas the size of the first layer; later layers are
 * scaled to it. A tinted layer is drawn through a `MULTIPLY` colour filter, so the greyscale
 * shading of the pack PNG survives and the tint's alpha scales the layer's alpha. A step with
 * [CompositeStep.gradientEndArgb] is tinted the same way with a top-left → bottom-right linear
 * gradient instead (a `ComposeShader` of the layer and the gradient in `MULTIPLY`).
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
 *
 * Two further job kinds carry no layers: a job with [PlannedDrawableJob.custom] is a user
 * imported PNG under `customDir/<icons|backgrounds>/<file>`, fitted into the slot's size by
 * [CustomSizing]; a job with [PlannedDrawableJob.synthesis] renders its reference pack icon
 * through the normal path and hands it to [IconSynthesizer] to draw the missing glyph in the
 * pack's style. A job with [PlannedDrawableJob.badge] is a built-in icon drawn from scratch by
 * [IconSynthesizer.drawBadge] at the slot size from [CustomSizing.iconSize]. These need the
 * optional constructor arguments; without them such a job throws.
 *
 * Performance notes: pack zips are opened once per renderer and kept until [close]; layers are
 * decoded straight from the zip stream (no intermediate byte array) except where the bytes are
 * shipped as they are; a custom image is sized from its header first and decoded with
 * `inSampleSize` when it is much larger than its slot, and is not decoded at all when it is
 * shipped unchanged; the `Paint`s and the PNG output buffer are reused across jobs. One instance
 * serves one thread at a time (an apply or a preview), never two concurrently.
 */
class DrawableRenderer(
    private val packs: LayeredPackResolver,
    private val customDir: File? = null,
    private val synthesizer: IconSynthesizer? = null,
) : Closeable {

    /** An apply: packs from one directory ([ThemeApplier.packsDir]) only. */
    constructor(packs: DirectoryPackResolver, customDir: File? = null, synthesizer: IconSynthesizer? = null) :
        this(LayeredPackResolver(packs), customDir, synthesizer)

    /**
     * [png] to fabricate. [ninePatch] asks the service to fabricate it as a 9-patch SOURCE
     * (marker border still present); since compilation happens here it is always false, both
     * for a compiled 9-patch (the PNG carries `npTc`) and for the plain-PNG fallbacks, whose
     * reason is in [warning]. The field stays so callers keep compiling.
     */
    class Rendered(val png: ByteArray, val ninePatch: Boolean, val warning: String? = null)

    private val zips = HashMap<String, ZipFile>()

    /** Reused for every layer; the colour filter is set per step and cleared afterwards. */
    private val layerPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** Reused for letterboxing custom icons. */
    private val fitPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    /** PNG encoder output, reset per encode; [ByteArrayOutputStream.toByteArray] copies out of it. */
    private var pngBuffer = ByteArrayOutputStream(PNG_BUFFER_BYTES)

    fun render(job: PlannedDrawableJob): Rendered {
        job.custom?.let { return renderCustom(job, it) }
        job.synthesis?.let { return renderSynthesis(job, it) }
        job.badge?.let { return renderBadge(job, it) }
        val steps = job.plan.steps
        if (steps.isEmpty()) throw IOException("no layers for ${job.resourceName}")
        val zip = zip(job.source.packId)
        if (steps.size == 1 && steps[0].tintArgb == null && steps[0].gradientEndArgb == null) {
            val bytes = read(zip, steps[0].layerPath)
            if (!job.ninePatch) return Rendered(bytes, ninePatch = false)
            return compileSingle(bytes, steps[0].layerPath)
        }

        val decoded = ArrayList<Bitmap>(steps.size)
        try {
            for (step in steps) decoded.add(decodeEntry(zip, step.layerPath))
            val out = composite(decoded, steps)
            try {
                val width = out.width
                val height = out.height
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

    /**
     * Draws [layers] bottom first onto a new ARGB bitmap the size of the first layer, each
     * through the MULTIPLY filter of the tint of its entry in [steps] (null = untinted), or, when
     * the step has a [CompositeStep.gradientEndArgb], through a MULTIPLY linear gradient from the
     * tint (top left) to that colour (bottom right). The caller owns the result and still owns
     * [layers].
     */
    private fun composite(layers: List<Bitmap>, steps: List<CompositeStep>): Bitmap {
        val base = layers[0]
        val width = base.width
        val height = base.height
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(out)
            val dst = Rect(0, 0, width, height)
            for (i in layers.indices) {
                val layer = layers[i]
                val tint = steps[i].tintArgb
                val end = steps[i].gradientEndArgb
                if (tint != null && end != null) {
                    layerPaint.colorFilter = null
                    layerPaint.shader = gradientShader(layer, width, height, tint, end)
                    canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), layerPaint)
                    layerPaint.shader = null
                } else {
                    layerPaint.colorFilter = if (tint != null) PorterDuffColorFilter(tint, PorterDuff.Mode.MULTIPLY) else null
                    canvas.drawBitmap(layer, null, dst, layerPaint)
                }
            }
            return out
        } catch (t: Throwable) {
            out.recycle()
            throw t
        } finally {
            layerPaint.colorFilter = null
            layerPaint.shader = null
        }
    }

    /**
     * [layer] scaled to [width]x[height], multiplied by a linear gradient from [start] at the top
     * left to [end] at the bottom right: the gradient counterpart of the MULTIPLY colour filter
     * (the layer's greyscale shading and alpha survive, the gradient's alpha scales the alpha).
     */
    private fun gradientShader(layer: Bitmap, width: Int, height: Int, start: Int, end: Int): Shader {
        val bitmapShader = BitmapShader(layer, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val matrix = Matrix()
        matrix.setScale(width.toFloat() / layer.width, height.toFloat() / layer.height)
        bitmapShader.setLocalMatrix(matrix)
        val gradient = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), start, end, Shader.TileMode.CLAMP)
        return ComposeShader(bitmapShader, gradient, PorterDuff.Mode.MULTIPLY)
    }

    /**
     * A built-in icon: a squircle badge with the app's glyph, drawn at the slot size of the
     * resource family ([CustomSizing.iconSize]: 46 dp for Settings rows, 40 dp for My Files
     * categories, times the configuration's density).
     */
    private fun renderBadge(job: PlannedDrawableJob, badge: IconBadge): Rendered {
        val synth = synthesizer ?: throw IOException("no synthesizer for the built-in icon ${job.resourceName}")
        val size = CustomSizing.iconSize(job.resourceName, job.configuration, BADGE_FALLBACK_PX, BADGE_FALLBACK_PX)
        val out = synth.drawBadge(badge.glyph, badge.fillArgb, badge.fillEndArgb, badge.glyphArgb, maxOf(size.width, size.height))
        try {
            return Rendered(encode(out), ninePatch = false)
        } finally {
            out.recycle()
        }
    }

    /**
     * A user-imported image. Icons are letterboxed (aspect kept, centred, transparent padding)
     * into the slot size from [CustomSizing.iconSize]; backgrounds are never upscaled and are
     * shrunk so the longer side is at most [CustomSizing.MAX_BACKGROUND_PX].
     *
     * The size decision is made from the PNG header alone: an image shipped as it is costs no
     * decode, and one that is shrunk is decoded with the largest power-of-two `inSampleSize`
     * that still leaves it at least the output size, so a 4096 px import never becomes a 64 MB
     * bitmap on the way to a 138 px icon.
     */
    private fun renderCustom(job: PlannedDrawableJob, file: String): Rendered {
        val root = customDir ?: throw IOException("no custom asset directory for ${job.resourceName}")
        val background = job.source.category == CustomAssets.CATEGORY_BACKGROUND
        val dir = File(root, if (background) CustomSizing.DIR_BACKGROUNDS else CustomSizing.DIR_ICONS)
        val source = File(dir, file)
        if (file.isEmpty() || file.contains('/') || file.contains('\\') || !source.isFile) {
            throw IOException("custom file $file for ${job.resourceName} is missing")
        }
        val bytes = source.readBytes()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) throw IOException("cannot decode ${source.name}")

        if (background) {
            val longer = maxOf(sourceWidth, sourceHeight)
            if (longer <= CustomSizing.MAX_BACKGROUND_PX) return Rendered(bytes, ninePatch = false)
            val scale = CustomSizing.MAX_BACKGROUND_PX.toFloat() / longer
            val targetWidth = maxOf(1, (sourceWidth * scale).roundToInt())
            val targetHeight = maxOf(1, (sourceHeight * scale).roundToInt())
            val bitmap = decodeSampled(bytes, source.name, sourceWidth, sourceHeight, targetWidth, targetHeight)
            try {
                val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
                try {
                    return Rendered(encode(scaled), ninePatch = false)
                } finally {
                    if (scaled !== bitmap) scaled.recycle()
                }
            } finally {
                bitmap.recycle()
            }
        }

        val size = CustomSizing.iconSize(job.resourceName, job.configuration, sourceWidth, sourceHeight)
        if (size.width == sourceWidth && size.height == sourceHeight) return Rendered(bytes, ninePatch = false)
        val bitmap = decodeSampled(bytes, source.name, sourceWidth, sourceHeight, size.width, size.height)
        try {
            val out = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
            try {
                val scale = min(size.width.toFloat() / bitmap.width, size.height.toFloat() / bitmap.height)
                val dw = maxOf(1, (bitmap.width * scale).roundToInt())
                val dh = maxOf(1, (bitmap.height * scale).roundToInt())
                val left = (size.width - dw) / 2
                val top = (size.height - dh) / 2
                Canvas(out).drawBitmap(bitmap, null, Rect(left, top, left + dw, top + dh), fitPaint)
                return Rendered(encode(out), ninePatch = false)
            } finally {
                out.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    /** A missing dashboard icon: render the reference pack icon, then draw the glyph in its style. */
    private fun renderSynthesis(job: PlannedDrawableJob, synthesis: IconSynthesis): Rendered {
        val synth = synthesizer ?: throw IOException("no synthesizer")
        val decoded = referenceBitmap(synthesis.reference)
        try {
            val out = synth.synthesize(decoded, synthesis.glyph, decoded.width, decoded.height)
            try {
                return Rendered(encode(out), ninePatch = false)
            } finally {
                out.recycle()
            }
        } finally {
            decoded.recycle()
        }
    }

    /**
     * The reference icon of a synthesis job as a bitmap. A plain pack layer job is decoded or
     * composited directly, skipping the PNG encode/decode round trip of [render]; anything else
     * (custom, nested synthesis, 9-patch) goes through [render] so the result is the same bitmap
     * the device would get.
     */
    private fun referenceBitmap(reference: PlannedDrawableJob): Bitmap {
        val steps = reference.plan.steps
        if (reference.custom != null || reference.synthesis != null || reference.badge != null || reference.ninePatch || steps.isEmpty()) {
            return decode(render(reference).png, reference.resourceName)
        }
        val zip = zip(reference.source.packId)
        if (steps.size == 1 && steps[0].tintArgb == null && steps[0].gradientEndArgb == null) return decodeEntry(zip, steps[0].layerPath)
        val decoded = ArrayList<Bitmap>(steps.size)
        try {
            for (step in steps) decoded.add(decodeEntry(zip, step.layerPath))
            return composite(decoded, steps)
        } finally {
            decoded.forEach { it.recycle() }
        }
    }

    private fun zip(packId: String): ZipFile {
        zips[packId]?.let { return it }
        val file = packs.zipFile(packId) ?: throw IOException("pack $packId is not installed")
        return ZipFile(file).also { zips[packId] = it }
    }

    private fun entry(zip: ZipFile, path: String): ZipEntry =
        zip.getEntry(path) ?: throw IOException("$path not found in ${zip.name.substringAfterLast('/')}")

    /**
     * The bytes of [path]; sized from the entry header when the zip records the size. Like
     * [PackReader.openLayer], an entry that declares or inflates to more than
     * [PackReader.MAX_ENTRY_BYTES] is refused with an [IOException], so a corrupt or hostile pack
     * cannot make this allocate past the heap (an OutOfMemoryError is not an Exception).
     */
    private fun read(zip: ZipFile, path: String): ByteArray {
        val entry = entry(zip, path)
        val size = entry.size
        if (size > PackReader.MAX_ENTRY_BYTES) throw IOException("$path is too large ($size bytes)")
        if (size < 0) return zip.getInputStream(entry).use { readBounded(it, path) }
        val bytes = ByteArray(size.toInt())
        zip.getInputStream(entry).use { DataInputStream(it).readFully(bytes) }
        return bytes
    }

    /** Reads [input] to the end, throwing once it yields more than [PackReader.MAX_ENTRY_BYTES]. */
    private fun readBounded(input: InputStream, path: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > PackReader.MAX_ENTRY_BYTES) throw IOException("$path inflates past ${PackReader.MAX_ENTRY_BYTES} bytes")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Decodes [path] straight from the zip stream, without copying its bytes first. The header is
     * read first ([checkPixels]), so an oversized entry or image fails with an [IOException]
     * instead of an OutOfMemoryError.
     */
    private fun decodeEntry(zip: ZipFile, path: String): Bitmap {
        val entry = entry(zip, path)
        if (entry.size > PackReader.MAX_ENTRY_BYTES) throw IOException("$path is too large (${entry.size} bytes)")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        zip.getInputStream(entry).use { BitmapFactory.decodeStream(it, null, bounds) }
        checkPixels(bounds, path)
        return zip.getInputStream(entry).use { decodeStream(it, path) }
    }

    private fun decodeStream(input: InputStream, path: String): Bitmap =
        BitmapFactory.decodeStream(input, null, decodeOptions()) ?: throw IOException("cannot decode $path")

    private fun decode(bytes: ByteArray, path: String): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        checkPixels(bounds, path)
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOptions()) ?: throw IOException("cannot decode $path")
    }

    /** Rejects a header-only decode result that is unreadable or larger than [MAX_LAYER_PIXELS]. */
    private fun checkPixels(bounds: BitmapFactory.Options, path: String) {
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) throw IOException("cannot decode $path")
        if (width.toLong() * height > MAX_LAYER_PIXELS) throw IOException("$path is too large (${width}x$height px)")
    }

    /**
     * Decodes [bytes] (of [sourceWidth]x[sourceHeight]) with the largest power-of-two
     * `inSampleSize` that keeps the result at least [targetWidth]x[targetHeight], so the
     * following scale is always a shrink.
     */
    private fun decodeSampled(bytes: ByteArray, path: String, sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Bitmap {
        var sample = 1
        while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) sample *= 2
        val options = decodeOptions().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: throw IOException("cannot decode $path")
    }

    private fun decodeOptions(): BitmapFactory.Options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inScaled = false
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

    /** PNG-encodes [bitmap] through the shared buffer; the returned array is a fresh copy. */
    private fun encode(bitmap: Bitmap): ByteArray {
        pngBuffer.reset()
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, pngBuffer)) throw IOException("PNG encoding failed")
        val bytes = pngBuffer.toByteArray()
        // A background can leave a multi-megabyte buffer behind. reset() keeps the grown array,
        // so replace the stream to let it be collected and keep the renderer small between jobs.
        if (bytes.size > PNG_BUFFER_KEEP_BYTES) pngBuffer = ByteArrayOutputStream(PNG_BUFFER_BYTES)
        return bytes
    }

    private companion object {
        /**
         * Most pixels one decoded pack layer may have: 4096x4096, 64 MB as ARGB. Real layers are
         * icons and panels well below this; the cap keeps a crafted PNG header from an OOM.
         */
        const val MAX_LAYER_PIXELS = 4096L * 4096L

        /** Built-in badge size when the resource is not a known icon family (never for real jobs). */
        const val BADGE_FALLBACK_PX = 144

        /** Initial PNG buffer: a 144 px ARGB icon encodes well under this. */
        const val PNG_BUFFER_BYTES = 128 * 1024

        /** Buffers that grew past this are not kept between jobs. */
        const val PNG_BUFFER_KEEP_BYTES = 4 * 1024 * 1024
    }
}

/**
 * Pixel sizes for user-imported icons. The compiler replaced the pack jobs for the resource, so
 * the slot size is not known from a pack layer; it is derived from the dp size One UI uses for
 * the resource family times the density of the job's configuration (xxhdpi when it names none).
 */
object CustomSizing {
    const val DIR_ICONS: String = "icons"
    const val DIR_BACKGROUNDS: String = "backgrounds"

    /** Longer side limit for backgrounds. */
    const val MAX_BACKGROUND_PX: Int = 2400

    /** Longer side limit for an icon whose family is unknown (kept at source size below it). */
    const val MAX_ICON_PX: Int = 512

    /** Settings dashboard row icons measure 138–144 px at xxhdpi in packs: 46 dp. */
    const val SETTINGS_ICON_DP: Int = 46

    /** My Files category / file type icons: 40 dp. */
    const val MY_FILES_ICON_DP: Int = 40

    const val DEFAULT_DENSITY: Int = 480

    /** An output size in px. */
    data class Px(val width: Int, val height: Int)

    /**
     * Output size in px for [resourceName] (`pkg:drawable/name`) in [configuration]: a dp-based
     * square for the known Settings / My Files families, else the source size (kept non-square),
     * scaled down uniformly only when its longer side exceeds [MAX_ICON_PX].
     */
    fun iconSize(resourceName: String, configuration: String, sourceWidth: Int, sourceHeight: Int): Px {
        val name = resourceName.substringAfterLast('/')
        val dp = when {
            name.startsWith("sec_ic_settings_") -> SETTINGS_ICON_DP
            name.startsWith("home_category_") || name.startsWith("file_type_") -> MY_FILES_ICON_DP
            else -> {
                val w = maxOf(sourceWidth, 1)
                val h = maxOf(sourceHeight, 1)
                val longer = maxOf(w, h)
                if (longer <= MAX_ICON_PX) return Px(w, h)
                val scale = MAX_ICON_PX.toFloat() / longer
                return Px(maxOf(1, (w * scale).roundToInt()), maxOf(1, (h * scale).roundToInt()))
            }
        }
        val px = maxOf(1, (dp * density(configuration) / 160f).roundToInt())
        return Px(px, px)
    }

    /** Density (dpi) named by a configuration such as `night-xxhdpi` or `420dpi`; [DEFAULT_DENSITY] when none. */
    fun density(configuration: String): Int {
        for (part in configuration.split('-')) {
            when (part) {
                "ldpi" -> return 120
                "mdpi" -> return 160
                "tvdpi" -> return 213
                "hdpi" -> return 240
                "xhdpi" -> return 320
                "xxhdpi" -> return 480
                "xxxhdpi" -> return 640
            }
            if (part.endsWith("dpi")) part.removeSuffix("dpi").toIntOrNull()?.let { if (it > 0) return it }
        }
        return DEFAULT_DENSITY
    }
}
