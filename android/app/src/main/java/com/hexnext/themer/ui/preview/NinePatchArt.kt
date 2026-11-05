package com.hexnext.themer.ui.preview

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * A nine-patch image as the phone stretches it: the 1 px guide border marks the stretchable
 * column and row (black on the top and left edges); corners keep their size, scaled from the
 * xxhdpi art. Art without guides is drawn stretched whole.
 */
internal class NinePatchArt private constructor(
    val image: ImageBitmap,
    private val content: IntRect,
    private val stretchX: IntRange,
    private val stretchY: IntRange,
) {
    data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int)

    fun DrawScope.drawArt(cornerScale: Float) {
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        if (w <= 0 || h <= 0) return
        val xs = cuts(content.left, stretchX, content.right)
        val ys = cuts(content.top, stretchY, content.bottom)
        val dx = targets(xs, w, cornerScale)
        val dy = targets(ys, h, cornerScale)
        for (i in 0..2) for (j in 0..2) {
            val sw = xs[i + 1] - xs[i]
            val sh = ys[j + 1] - ys[j]
            val tw = dx[i + 1] - dx[i]
            val th = dy[j + 1] - dy[j]
            if (sw <= 0 || sh <= 0 || tw <= 0 || th <= 0) continue
            drawImage(
                image,
                srcOffset = IntOffset(xs[i], ys[j]), srcSize = IntSize(sw, sh),
                dstOffset = IntOffset(dx[i], dy[j]), dstSize = IntSize(tw, th),
            )
        }
    }

    private fun cuts(start: Int, stretch: IntRange, end: Int) = intArrayOf(start, stretch.first, stretch.last + 1, end)

    /** Destination edges: the fixed parts scaled by [scale] (shrunk to fit), the middle takes the rest. */
    private fun targets(src: IntArray, total: Int, scale: Float): IntArray {
        var a = ((src[1] - src[0]) * scale).roundToInt()
        var c = ((src[3] - src[2]) * scale).roundToInt()
        if (a + c > total) {
            val f = total.toFloat() / (a + c)
            a = (a * f).toInt(); c = (c * f).toInt()
        }
        return intArrayOf(0, a, total - c, total)
    }

    companion object {
        fun of(image: ImageBitmap): NinePatchArt {
            val bitmap = image.asAndroidBitmap()
            val w = bitmap.width
            val h = bitmap.height
            fun isGuide(x: Int, y: Int): Boolean {
                val p = bitmap.getPixel(x, y)
                return (p ushr 24) == 0xFF && (p and 0xFFFFFF) == 0
            }
            if (w < 3 || h < 3) return NinePatchArt(image, IntRect(0, 0, w, h), 0 until w, 0 until h)
            val top = (1 until w - 1).filter { isGuide(it, 0) }
            val left = (1 until h - 1).filter { isGuide(0, it) }
            val corner = bitmap.getPixel(0, 0) ushr 24 == 0
            return if (corner && top.isNotEmpty() && left.isNotEmpty()) {
                NinePatchArt(image, IntRect(1, 1, w - 1, h - 1), top.first()..top.last(), left.first()..left.last())
            } else {
                NinePatchArt(image, IntRect(0, 0, w, h), w / 2..w / 2, h / 2..h / 2)
            }
        }
    }
}

/** Draws [art] behind the content, its corners at [cornerScale] × the art's pixels. */
internal fun Modifier.ninePatch(art: NinePatchArt, cornerScale: Float): Modifier = drawBehind {
    with(art) { drawArt(cornerScale) }
}
