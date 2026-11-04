package com.hexnext.themer.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.content.res.ResourcesCompat
import com.hexnext.themer.R
import java.io.IOException
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws a dashboard icon the pack does not ship, in the pack's own style.
 *
 * The reference is a rendered pack icon of the same configuration (typically a 138–144 px round
 * or squircle badge with a flat glyph). [synthesize] analyses it: the badge shape is every pixel
 * with alpha > 16, the badge colour the median of a ring at 72–95 % of the half-diagonal, the
 * glyph every badge pixel whose colour is far from the badge colour at its radius, the glyph
 * colour the median of those pixels. The glyph pixels are then inpainted with the per-radius
 * ring colour (so gradients survive), and one of the `hx_glyph_*` VectorDrawables is drawn on
 * top, tinted with the glyph colour, centred, sized like the reference glyph.
 *
 * [drawBadge] draws a built-in icon from scratch (no pack): a One UI style squircle in a flat
 * colour or a linear gradient, a soft top highlight, and the glyph at [DEFAULT_GLYPH_FRACTION].
 */
class IconSynthesizer(context: Context) {

    private val appContext = context.applicationContext

    /**
     * [reference]: a rendered pack icon; [glyph]: a dashboard row key such as `galaxy_ai`;
     * returns an ARGB bitmap of [outW]x[outH]. Throws [IOException] for an unknown glyph.
     */
    fun synthesize(reference: Bitmap, glyph: String, outW: Int, outH: Int): Bitmap {
        val drawable = glyphDrawable(glyph)
        val w = reference.width
        val h = reference.height
        if (w <= 0 || h <= 0) throw IOException("empty reference icon")
        val pixels = IntArray(w * h)
        reference.getPixels(pixels, 0, w, 0, 0, w, h)
        val analysis = analyse(pixels, w, h)

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(analysis.background, 0, w, 0, 0, w, h)

        val size = analysis.glyphSize
        val cx = analysis.centerX
        val cy = analysis.centerY
        val canvas = Canvas(out)
        drawable.setBounds(
            (cx - size / 2f).roundToInt(), (cy - size / 2f).roundToInt(),
            (cx + size / 2f).roundToInt(), (cy + size / 2f).roundToInt(),
        )
        drawable.colorFilter = PorterDuffColorFilter(analysis.glyphColor, PorterDuff.Mode.SRC_IN)
        drawable.isFilterBitmap = true
        drawable.draw(canvas)

        if (w == outW && h == outH) return out
        val scaled = Bitmap.createScaledBitmap(out, max(1, outW), max(1, outH), true)
        if (scaled !== out) out.recycle()
        return scaled
    }

    /**
     * A built-in icon of [sizePx] square: a squircle filled with [fill] (a top-left → bottom-right
     * gradient to [fillEnd] when set) and the `hx_glyph_*` art of [glyph] in [glyphColor], centred
     * at about 52 % of the size. Throws [IOException] for an unknown glyph.
     */
    fun drawBadge(glyph: String, fill: Int, fillEnd: Int?, glyphColor: Int, sizePx: Int): Bitmap {
        val drawable = glyphDrawable(glyph)
        val size = sizePx.coerceIn(1, MAX_BADGE_PX)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val inset = size * BADGE_INSET
        val left = inset
        val top = inset
        val right = size - inset
        val bottom = size - inset
        val path = squircle(left, top, right, bottom)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.style = Paint.Style.FILL
        if (fillEnd != null && fillEnd != fill) {
            paint.shader = LinearGradient(left, top, right, bottom, opaque(fill), opaque(fillEnd), Shader.TileMode.CLAMP)
        } else {
            paint.color = opaque(fill)
        }
        canvas.drawPath(path, paint)

        // A soft light from the top keeps flat colours from looking printed.
        paint.color = Color.WHITE
        paint.shader = LinearGradient(
            0f, top, 0f, top + (bottom - top) * HIGHLIGHT_DEPTH,
            Color.argb(HIGHLIGHT_ALPHA, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, paint)
        paint.shader = null

        val glyphSize = size * DEFAULT_GLYPH_FRACTION
        val c = size / 2f
        drawable.setBounds(
            (c - glyphSize / 2f).roundToInt(), (c - glyphSize / 2f).roundToInt(),
            (c + glyphSize / 2f).roundToInt(), (c + glyphSize / 2f).roundToInt(),
        )
        drawable.colorFilter = PorterDuffColorFilter(opaque(glyphColor), PorterDuff.Mode.SRC_IN)
        drawable.isFilterBitmap = true
        drawable.draw(canvas)
        return out
    }

    /** A superellipse (|x|^n + |y|^n = 1, n = [SQUIRCLE_EXPONENT]) filling the given box. */
    private fun squircle(left: Float, top: Float, right: Float, bottom: Float): Path {
        val cx = (left + right) / 2f
        val cy = (top + bottom) / 2f
        val rx = (right - left) / 2f
        val ry = (bottom - top) / 2f
        val path = Path()
        val exponent = 2.0 / SQUIRCLE_EXPONENT
        for (i in 0 until SQUIRCLE_POINTS) {
            val t = 2.0 * Math.PI * i / SQUIRCLE_POINTS
            val ct = cos(t)
            val st = sin(t)
            val x = cx + rx * (sign(ct) * abs(ct).pow(exponent)).toFloat()
            val y = cy + ry * (sign(st) * abs(st).pow(exponent)).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun glyphDrawable(glyph: String): Drawable {
        val id = GLYPHS[glyph] ?: throw IOException("no glyph for dashboard row \"$glyph\"")
        val drawable = ResourcesCompat.getDrawable(appContext.resources, id, appContext.theme)
            ?: throw IOException("glyph $glyph could not be loaded")
        return drawable.mutate()
    }

    /** Results of the reference analysis; [background] is the reference with the glyph inpainted. */
    private class Analysis(
        val background: IntArray,
        val glyphColor: Int,
        val centerX: Float,
        val centerY: Float,
        val glyphSize: Float,
    )

    private fun analyse(pixels: IntArray, w: Int, h: Int): Analysis {
        val n = w * h
        val shape = BooleanArray(n) { Color.alpha(pixels[it]) > ALPHA_SHAPE }
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        var shapeCount = 0
        for (i in 0 until n) {
            if (!shape[i]) continue
            shapeCount++
            val x = i % w; val y = i / w
            if (x < minX) minX = x
            if (x > maxX) maxX = x
            if (y < minY) minY = y
            if (y > maxY) maxY = y
        }
        if (shapeCount == 0) {
            // Fully transparent reference: draw a white glyph in the middle at the default size.
            return Analysis(pixels.copyOf(), Color.WHITE, w / 2f, h / 2f, min(w, h) * DEFAULT_GLYPH_FRACTION)
        }
        val cx = (minX + maxX + 1) / 2f
        val cy = (minY + maxY + 1) / 2f
        val shapeW = maxX - minX + 1
        val shapeH = maxY - minY + 1
        val shapeSize = min(shapeW, shapeH).toFloat()
        val halfDiagonal = sqrt((shapeW * shapeW + shapeH * shapeH).toFloat()) / 2f
        outlineAnalysis(pixels, shape, w, h, minX, minY, maxX, maxY, cx, cy, shapeSize)?.let { return it }

        // Radius of every pixel, in bins of 1 px, relative to the bounding-box centre.
        val radius = FloatArray(n)
        var maxRadius = 0f
        for (i in 0 until n) {
            if (!shape[i]) continue
            val dx = (i % w) + 0.5f - cx
            val dy = (i / w) + 0.5f - cy
            val r = sqrt(dx * dx + dy * dy)
            radius[i] = r
            if (r > maxRadius) maxRadius = r
        }
        val bins = maxRadius.toInt() + 2

        // Badge colour: median of the shape pixels in the outer ring.
        val ringLo = halfDiagonal * RING_INNER
        val ringHi = halfDiagonal * RING_OUTER
        val ring = IntList(shapeCount / 2)
        for (i in 0 until n) if (shape[i] && radius[i] >= ringLo && radius[i] <= ringHi) ring.add(pixels[i])
        if (ring.isEmpty()) for (i in 0 until n) if (shape[i] && radius[i] >= halfDiagonal * 0.5f) ring.add(pixels[i])
        if (ring.isEmpty()) for (i in 0 until n) if (shape[i]) ring.add(pixels[i])
        val bgColor = medianColor(ring)

        // Glyph mask: pixels far from the per-radius mean of the non-glyph pixels; two passes.
        var glyph = BooleanArray(n)
        var ringMean = IntArray(bins)
        repeat(2) {
            ringMean = perRadiusMean(pixels, shape, glyph, radius, bins, bgColor)
            val next = BooleanArray(n)
            for (i in 0 until n) {
                if (!shape[i]) continue
                val mean = ringMean[radius[i].toInt()]
                if (colorDistance(pixels[i], mean) > GLYPH_DISTANCE) next[i] = true
            }
            glyph = next
        }
        var glyphCount = 0
        var gMinX = w; var gMinY = h; var gMaxX = -1; var gMaxY = -1
        val glyphPixels = IntList(shapeCount / 4)
        for (i in 0 until n) {
            if (!glyph[i]) continue
            glyphCount++
            glyphPixels.add(pixels[i])
            val x = i % w; val y = i / w
            if (x < gMinX) gMinX = x
            if (x > gMaxX) gMaxX = x
            if (y < gMinY) gMinY = y
            if (y > gMaxY) gMaxY = y
        }

        val hasGlyph = glyphCount >= shapeCount * MIN_GLYPH_FRACTION
        val glyphColor = if (hasGlyph) {
            opaque(medianColor(glyphPixels))
        } else {
            if (luminance(bgColor) > 0.5) Color.BLACK else Color.WHITE
        }

        // Background: the reference with glyph pixels replaced by the ring mean at their radius.
        val background = pixels.copyOf()
        if (hasGlyph) {
            for (i in 0 until n) if (glyph[i]) background[i] = withAlpha(ringMean[radius[i].toInt()], Color.alpha(pixels[i]))
        }

        val minSize = shapeSize * MIN_GLYPH_SIZE
        val maxSize = shapeSize * MAX_GLYPH_SIZE
        val glyphSize = if (hasGlyph) {
            max(gMaxX - gMinX + 1, gMaxY - gMinY + 1).toFloat().coerceIn(minSize, maxSize)
        } else {
            shapeSize * DEFAULT_GLYPH_FRACTION
        }
        return Analysis(background, glyphColor, cx, cy, glyphSize)
    }

    /**
     * An outline icon (a frame such as a ring or hexagon line, its middle see-through, the glyph
     * floating inside, as in pack power menu art): the frame is every shape pixel connected to the
     * bounding box edge, the glyph everything else. The glyph is erased to transparent and its
     * colour and size are kept. Null when the middle of the shape is mostly opaque (a filled
     * badge, handled by the radius analysis) or nothing inside is separate from the frame.
     */
    private fun outlineAnalysis(
        pixels: IntArray, shape: BooleanArray, w: Int, h: Int,
        minX: Int, minY: Int, maxX: Int, maxY: Int, cx: Float, cy: Float, shapeSize: Float,
    ): Analysis? {
        val n = w * h
        val probe = shapeSize * OUTLINE_PROBE
        var inside = 0
        var opaque = 0
        for (i in 0 until n) {
            val dx = (i % w) + 0.5f - cx
            val dy = (i / w) + 0.5f - cy
            if (dx * dx + dy * dy > probe * probe) continue
            inside++
            if (shape[i]) opaque++
        }
        if (inside == 0 || opaque >= inside * OUTLINE_MAX_FILL) return null

        // Flood the frame from every shape pixel on the bounding box edge (4-neighbour).
        val frame = BooleanArray(n)
        val stack = IntList(256)
        fun seed(i: Int) { if (shape[i] && !frame[i]) { frame[i] = true; stack.add(i) } }
        for (x in minX..maxX) { seed(minY * w + x); seed(maxY * w + x) }
        for (y in minY..maxY) { seed(y * w + minX); seed(y * w + maxX) }
        while (!stack.isEmpty()) {
            val i = stack.pop()
            val x = i % w
            val y = i / w
            if (x > 0) seed(i - 1)
            if (x < w - 1) seed(i + 1)
            if (y > 0) seed(i - w)
            if (y < h - 1) seed(i + w)
        }
        val glyphPixels = IntList(64)
        var gMinX = w; var gMinY = h; var gMaxX = -1; var gMaxY = -1
        val background = pixels.copyOf()
        for (i in 0 until n) {
            if (!shape[i] || frame[i]) continue
            glyphPixels.add(pixels[i])
            background[i] = 0
            val x = i % w; val y = i / w
            if (x < gMinX) gMinX = x
            if (x > gMaxX) gMaxX = x
            if (y < gMinY) gMinY = y
            if (y > gMaxY) gMaxY = y
        }
        if (glyphPixels.size < 8) return null
        val glyphSize = max(gMaxX - gMinX + 1, gMaxY - gMinY + 1).toFloat() / OUTLINE_GLYPH_ART
        return Analysis(
            background, opaque(medianColor(glyphPixels)), cx, cy,
            glyphSize.coerceIn(shapeSize * MIN_OUTLINE_GLYPH, shapeSize * MAX_GLYPH_SIZE),
        )
    }

    /**
     * Mean colour per 1 px radius bin over shape pixels NOT in [exclude]; bins without pixels
     * borrow the nearest filled bin, or [fallback].
     */
    private fun perRadiusMean(pixels: IntArray, shape: BooleanArray, exclude: BooleanArray, radius: FloatArray, bins: Int, fallback: Int): IntArray {
        val sumA = LongArray(bins); val sumR = LongArray(bins); val sumG = LongArray(bins); val sumB = LongArray(bins)
        val count = IntArray(bins)
        for (i in pixels.indices) {
            if (!shape[i] || exclude[i]) continue
            val b = radius[i].toInt()
            val c = pixels[i]
            sumA[b] += Color.alpha(c).toLong(); sumR[b] += Color.red(c).toLong()
            sumG[b] += Color.green(c).toLong(); sumB[b] += Color.blue(c).toLong()
            count[b]++
        }
        val mean = IntArray(bins)
        val filled = BooleanArray(bins)
        for (b in 0 until bins) {
            if (count[b] == 0) continue
            mean[b] = Color.argb(
                (sumA[b] / count[b]).toInt(), (sumR[b] / count[b]).toInt(),
                (sumG[b] / count[b]).toInt(), (sumB[b] / count[b]).toInt(),
            )
            filled[b] = true
        }
        for (b in 0 until bins) {
            if (filled[b]) continue
            var d = 1
            var found: Int? = null
            while (found == null && (b - d >= 0 || b + d < bins)) {
                if (b - d >= 0 && filled[b - d]) found = mean[b - d]
                else if (b + d < bins && filled[b + d]) found = mean[b + d]
                d++
            }
            mean[b] = found ?: fallback
        }
        return mean
    }

    /**
     * Alpha-weighted RGB Euclidean distance (0..~441). Alpha only weighs the distance down; an
     * alpha difference alone is no distance, so the anti-aliased rim of the badge (badge colour at
     * partial alpha) is never taken for glyph.
     */
    private fun colorDistance(a: Int, b: Int): Float {
        val dr = Color.red(a) - Color.red(b)
        val dg = Color.green(a) - Color.green(b)
        val db = Color.blue(a) - Color.blue(b)
        val alpha = min(Color.alpha(a), Color.alpha(b)) / 255f
        return sqrt((dr * dr + dg * dg + db * db).toFloat()) * alpha
    }

    /** Component-wise median colour of [colors]; transparent black for an empty list. */
    private fun medianColor(colors: IntList): Int {
        if (colors.isEmpty()) return 0
        val data = colors.data
        fun median(select: (Int) -> Int): Int {
            val values = IntArray(colors.size) { select(data[it]) }
            values.sort()
            return values[values.size / 2]
        }
        return Color.argb(median { Color.alpha(it) }, median { Color.red(it) }, median { Color.green(it) }, median { Color.blue(it) })
    }

    /**
     * A growable list of ints without boxing: a 144 px reference has ~20 000 badge pixels, and
     * an `ArrayList<Int>` of them costs an object per pixel on every synthesised icon.
     */
    private class IntList(capacity: Int) {
        var data: IntArray = IntArray(maxOf(capacity, 16))
            private set
        var size: Int = 0
            private set

        fun add(value: Int) {
            if (size == data.size) data = data.copyOf(size * 2)
            data[size++] = value
        }

        fun isEmpty(): Boolean = size == 0

        fun pop(): Int = data[--size]
    }

    private fun opaque(color: Int): Int = color or (0xFF shl 24)

    private fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    private fun luminance(color: Int): Double =
        (0.2126 * Color.red(color) + 0.7152 * Color.green(color) + 0.0722 * Color.blue(color)) / 255.0

    companion object {
        private const val ALPHA_SHAPE = 16
        private const val RING_INNER = 0.72f
        private const val RING_OUTER = 0.95f
        private const val GLYPH_DISTANCE = 48f
        private const val MIN_GLYPH_FRACTION = 0.01f
        private const val MIN_GLYPH_SIZE = 0.40f
        private const val MAX_GLYPH_SIZE = 0.62f
        private const val DEFAULT_GLYPH_FRACTION = 0.52f

        /** Outline icons: radius (× shape size) of the middle probed for see-through pixels. */
        private const val OUTLINE_PROBE = 0.22f

        /** Outline icons: the middle is see-through when less than this share of it is opaque. */
        private const val OUTLINE_MAX_FILL = 0.5f

        /** Outline icons: the glyph art fills about this share of its 24 dp box. */
        private const val OUTLINE_GLYPH_ART = 0.75f
        private const val MIN_OUTLINE_GLYPH = 0.30f

        /** Transparent margin around a built-in badge, as a fraction of its size per side. */
        private const val BADGE_INSET = 0.04f
        private const val SQUIRCLE_EXPONENT = 4.6
        private const val SQUIRCLE_POINTS = 128
        private const val HIGHLIGHT_ALPHA = 46
        private const val HIGHLIGHT_DEPTH = 0.6f

        /** Largest built-in badge drawn: far above any real icon slot (46 dp at 640 dpi is 184 px). */
        private const val MAX_BADGE_PX = 1024

        /** Dashboard row or built-in glyph key → original Hex Next glyph (see `tools/glyphs/build.js`). */
        val GLYPHS: Map<String, Int> = mapOf(
            "cloud_and_account" to R.drawable.hx_glyph_cloud_and_account,
            "google" to R.drawable.hx_glyph_google,
            "connections" to R.drawable.hx_glyph_connections,
            "devicecare" to R.drawable.hx_glyph_devicecare,
            "applications" to R.drawable.hx_glyph_applications,
            "display" to R.drawable.hx_glyph_display,
            "advanced_feature" to R.drawable.hx_glyph_advanced_feature,
            "wallpaper" to R.drawable.hx_glyph_wallpaper,
            "general_device_management" to R.drawable.hx_glyph_general_device_management,
            "battery" to R.drawable.hx_glyph_battery,
            "galaxy_ai" to R.drawable.hx_glyph_galaxy_ai,
            "sound" to R.drawable.hx_glyph_sound,
            "notifications" to R.drawable.hx_glyph_notifications,
            "themes" to R.drawable.hx_glyph_themes,
            "biometrics_and_security" to R.drawable.hx_glyph_biometrics_and_security,
            "lockscreen" to R.drawable.hx_glyph_lockscreen,
            "accessibility" to R.drawable.hx_glyph_accessibility,
            "location" to R.drawable.hx_glyph_location,
            "multi_devices" to R.drawable.hx_glyph_multi_devices,
            "emergency" to R.drawable.hx_glyph_emergency,
            "user_manual" to R.drawable.hx_glyph_user_manual,
            "about" to R.drawable.hx_glyph_about,
            "digitalwellbeing" to R.drawable.hx_glyph_digitalwellbeing,
            "homescreen" to R.drawable.hx_glyph_homescreen,
            "modes" to R.drawable.hx_glyph_modes,
            "software_update" to R.drawable.hx_glyph_software_update,
            "tips" to R.drawable.hx_glyph_tips,
            // Power menu buttons (ThemeCompiler's power menu synthesis).
            "emergency_sos" to R.drawable.hx_glyph_emergency_sos,
            "medical_info" to R.drawable.hx_glyph_medical_info,
            // My Files categories (built-in icons only; IconColors names them `myfiles_<category>`).
            "myfiles_images" to R.drawable.hx_glyph_myfiles_images,
            "myfiles_videos" to R.drawable.hx_glyph_myfiles_videos,
            "myfiles_audio" to R.drawable.hx_glyph_myfiles_audio,
            "myfiles_documents" to R.drawable.hx_glyph_myfiles_documents,
            "myfiles_downloads" to R.drawable.hx_glyph_myfiles_downloads,
            "myfiles_apk" to R.drawable.hx_glyph_myfiles_apk,
        )
    }
}
