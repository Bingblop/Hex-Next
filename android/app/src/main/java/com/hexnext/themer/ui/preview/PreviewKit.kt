package com.hexnext.themer.ui.preview

import android.graphics.Typeface as AndroidTypeface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.themer.ui.LocalAppSettings
import com.hexnext.themer.ui.studio.PreviewIcon
import com.hexnext.themer.ui.studio.PreviewScheme
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* Shared drawing helpers of the phone mockup. Everything here draws from plain values. */

/** The console cyan used to outline the colour group being edited. */
internal val HighlightCyan = Color(0xFF22D3EE)

/** Near-black ink for text on light colours. */
private val Ink = Color(0xFF101318)

internal fun Int.toColor(): Color = Color(this)

/** WCAG contrast ratio of two opaque colours (1 … 21). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.copy(alpha = 1f).luminance()
    val lb = b.copy(alpha = 1f).luminance()
    return (max(la, lb) + 0.05f) / (min(la, lb) + 0.05f)
}

/** White or near-black, whichever reads better on [background]. */
internal fun contentColorFor(background: Color): Color =
    if (contrastRatio(Ink, background) >= contrastRatio(Color.White, background)) Ink else Color.White

/** [preferred] when it is readable on [background], else the contrast pick. */
internal fun readableOn(preferred: Color, background: Color, minRatio: Float = 3f): Color =
    if (contrastRatio(preferred, background) >= minRatio) preferred else contentColorFor(background)

/** The colours of one mode, animated towards the scheme unless reduce motion is on. */
@Immutable
internal class PreviewPalette(
    private val scheme: PreviewScheme,
    private val colors: Map<ColorGroup, Color>,
) {
    val dark: Boolean get() = scheme.dark

    operator fun get(group: ColorGroup): Color = colors[group] ?: scheme.color(group).toColor()

    /** A Monet shade (`accent1_300`) for decorative art, [fallback] when the scheme lacks it. */
    fun shade(key: String, fallback: Color): Color = scheme.shade(key)?.toColor() ?: fallback
}

/** The colours of [scheme], animated on every change (instant with reduce motion). */
@Composable
internal fun animatedPalette(scheme: PreviewScheme): PreviewPalette {
    val reduceMotion = LocalAppSettings.current.reduceMotion
    val colors = LinkedHashMap<ColorGroup, Color>(ColorGroup.entries.size)
    for (group in ColorGroup.entries) {
        val target = scheme.color(group).toColor()
        colors[group] = if (reduceMotion) {
            target
        } else {
            animateColorAsState(target, animationSpec = tween(durationMillis = 260), label = "preview_${group.id}").value
        }
    }
    return PreviewPalette(scheme, colors)
}

/** What every preview screen needs: colours, the highlighted group, the tap callback and the font. */
@Stable
internal class PreviewScope(
    val palette: PreviewPalette,
    val highlighted: ColorGroup?,
    val onTap: (ColorGroup) -> Unit,
    val font: FontFamily?,
    val reduceMotion: Boolean,
) {
    val dark: Boolean get() = palette.dark

    operator fun get(group: ColorGroup): Color = palette[group]

    /** The contrast pick on [group]'s colour. */
    fun on(group: ColorGroup): Color = contentColorFor(palette[group])

    /** [group] (a text group) when readable on [background], else the contrast pick. */
    fun text(background: Color, group: ColorGroup = ColorGroup.TEXT_PRIMARY): Color =
        readableOn(palette[group], background, if (group == ColorGroup.TEXT_SECONDARY) 2.6f else 3.2f)
}

/**
 * Makes an element tappable as [group]: calls [onTap], reads "<label>, edit colour" to
 * accessibility services, and draws a dashed cyan outline (in [shape]) when [group] is
 * [highlighted].
 */
internal fun Modifier.themed(
    group: ColorGroup,
    highlighted: ColorGroup?,
    onTap: (ColorGroup) -> Unit,
    shape: Shape = RectangleShape,
): Modifier {
    val outline = if (highlighted == group) {
        Modifier.drawWithContent {
            drawContent()
            val stroke = 2.dp.toPx()
            inset(stroke / 2f) {
                val shapeOutline = shape.createOutline(size, layoutDirection, this)
                drawOutline(shapeOutline, HighlightCyan.copy(alpha = 0.14f))
                drawOutline(
                    shapeOutline,
                    HighlightCyan,
                    style = Stroke(
                        width = stroke,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 3.dp.toPx())),
                    ),
                )
            }
        }
    } else {
        Modifier
    }
    return this
        .then(outline)
        .semantics { contentDescription = "${group.label}, edit colour" }
        .clickable(onClickLabel = "Edit colour", role = Role.Button) { onTap(group) }
}

/** [themed] with the scope's highlight and callback. */
internal fun Modifier.themed(group: ColorGroup, scope: PreviewScope, shape: Shape = RectangleShape): Modifier =
    this.themed(group, scope.highlighted, scope.onTap, shape)

/** Fills the element with [group]'s colour (or [color]) in [shape] and makes it tappable. */
internal fun Modifier.fill(
    group: ColorGroup,
    scope: PreviewScope,
    shape: Shape = RectangleShape,
    color: Color = scope[group],
): Modifier = this.clip(shape).background(color).themed(group, scope, shape)

private val TextTapShape = RoundedCornerShape(4.dp)

/** One line (or [maxLines]) of preview text in the theme font. */
@Composable
internal fun PText(
    text: String,
    color: Color,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
    size: TextUnit = 12.sp,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
    letterSpacing: TextUnit = 0.sp,
) {
    Text(
        text = text,
        modifier = modifier,
        style = TextStyle(
            color = color,
            fontSize = size,
            fontWeight = weight,
            fontFamily = scope.font,
            letterSpacing = letterSpacing,
            textAlign = align,
            lineHeight = size * 1.22f,
        ),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

/** Text in [group]'s colour (kept readable on [background]) that is tappable as [group]. */
@Composable
internal fun GroupText(
    text: String,
    group: ColorGroup,
    background: Color,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
    size: TextUnit = 12.sp,
    weight: FontWeight = FontWeight.Normal,
    maxLines: Int = 1,
    align: TextAlign = TextAlign.Start,
) {
    PText(
        text = text,
        color = scope.text(background, group),
        scope = scope,
        modifier = modifier.themed(group, scope, TextTapShape),
        size = size,
        weight = weight,
        maxLines = maxLines,
        align = align,
    )
}

/** The theme font loaded from [path] off the main thread, null for the system font. */
@Composable
internal fun rememberPreviewFontFamily(path: String?): FontFamily? {
    val state = produceState(initialValue = path?.let(PreviewFontCache::peek), key1 = path) {
        value = path?.let { p -> withContext(Dispatchers.IO) { PreviewFontCache.load(File(p)) } }
    }
    return state.value
}

/** The last few preview fonts by path; a changed file is parsed again. */
private object PreviewFontCache {
    private const val MAX_ENTRIES = 4

    private class Cached(val stamp: String, val family: FontFamily)

    private val entries = object : LinkedHashMap<String, Cached>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?): Boolean = size > MAX_ENTRIES
    }

    @Synchronized
    fun peek(path: String): FontFamily? = entries[path]?.family

    fun load(file: File): FontFamily? {
        val stamp = "${file.length()}:${file.lastModified()}"
        synchronized(this) { entries[file.path]?.takeIf { it.stamp == stamp }?.let { return it.family } }
        val family = runCatching { FontFamily(Typeface(AndroidTypeface.createFromFile(file))) }.getOrNull()
        synchronized(this) {
            if (family != null) entries[file.path] = Cached(stamp, family) else entries.remove(file.path)
        }
        return family
    }
}

/**
 * An app icon: the rendered bitmap when present, else a circle in its fallback colour with a
 * simple white glyph. While icons render, fallbacks pulse gently.
 */
@Composable
internal fun PreviewIconImage(
    icon: PreviewIcon,
    loading: Boolean,
    reduceMotion: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 28.dp,
) {
    val bitmap = icon.bitmap
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier.size(size),
            contentScale = ContentScale.Fit,
        )
        return
    }
    val pulse = if (loading && !reduceMotion) {
        val transition = rememberInfiniteTransition(label = "iconShimmer")
        val value by transition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 850), RepeatMode.Reverse),
            label = "iconShimmerAlpha",
        )
        value
    } else {
        1f
    }
    val fill = icon.fallbackArgb.toColor().copy(alpha = 1f)
    Box(
        modifier = modifier
            .size(size)
            .alpha(pulse)
            .clip(CircleShape)
            .background(fill),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(size * 0.36f)
                .clip(RoundedCornerShape(size * 0.1f))
                .background(contentColorFor(fill).copy(alpha = 0.88f)),
        )
    }
}

/** Simple vector glyphs drawn with shapes (the app ships only the core icon set). */
internal enum class Glyph { WIFI, BLUETOOTH, SIGNAL, BATTERY, MOON, DND, BOLT, SUN, VOLUME, POWER, TORCH }

/**
 * Draws [glyph] in [color]. [cutout] is the colour behind the glyph, used for the moon and
 * do-not-disturb cut-outs.
 */
@Composable
internal fun GlyphIcon(
    glyph: Glyph,
    color: Color,
    modifier: Modifier = Modifier,
    cutout: Color = Color.Transparent,
) {
    Canvas(modifier) {
        when (glyph) {
            Glyph.WIFI -> drawWifi(color)
            Glyph.BLUETOOTH -> drawBluetooth(color)
            Glyph.SIGNAL -> drawSignal(color)
            Glyph.BATTERY -> drawBattery(color)
            Glyph.MOON -> {
                val r = size.minDimension * 0.38f
                drawCircle(color, r, center)
                drawCircle(cutout, r * 0.86f, Offset(center.x + r * 0.55f, center.y - r * 0.45f))
            }
            Glyph.DND -> {
                val r = size.minDimension * 0.4f
                drawCircle(color, r, center)
                drawRoundRect(
                    cutout,
                    topLeft = Offset(center.x - r * 0.6f, center.y - r * 0.14f),
                    size = Size(r * 1.2f, r * 0.28f),
                    cornerRadius = CornerRadius(r * 0.14f),
                )
            }
            Glyph.BOLT -> {
                val w = size.width
                val h = size.height
                val path = Path().apply {
                    moveTo(w * 0.58f, h * 0.08f)
                    lineTo(w * 0.24f, h * 0.56f)
                    lineTo(w * 0.48f, h * 0.56f)
                    lineTo(w * 0.40f, h * 0.92f)
                    lineTo(w * 0.76f, h * 0.42f)
                    lineTo(w * 0.52f, h * 0.42f)
                    close()
                }
                drawPath(path, color)
            }
            Glyph.SUN -> drawSun(color)
            Glyph.VOLUME -> drawVolume(color)
            Glyph.POWER -> {
                val s = size.minDimension
                val stroke = Stroke(width = s * 0.11f, cap = StrokeCap.Round)
                val r = s * 0.32f
                drawArc(
                    color,
                    startAngle = -60f,
                    sweepAngle = 300f,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r + s * 0.04f),
                    size = Size(r * 2, r * 2),
                    style = stroke,
                )
                drawLine(color, Offset(center.x, s * 0.12f), Offset(center.x, center.y), s * 0.11f, StrokeCap.Round)
            }
            Glyph.TORCH -> {
                val w = size.width
                val h = size.height
                val head = Path().apply {
                    moveTo(w * 0.26f, h * 0.14f)
                    lineTo(w * 0.74f, h * 0.14f)
                    lineTo(w * 0.64f, h * 0.40f)
                    lineTo(w * 0.36f, h * 0.40f)
                    close()
                }
                drawPath(head, color)
                drawRoundRect(
                    color,
                    topLeft = Offset(w * 0.38f, h * 0.44f),
                    size = Size(w * 0.24f, h * 0.44f),
                    cornerRadius = CornerRadius(w * 0.05f),
                )
            }
        }
    }
}

private fun DrawScope.drawWifi(color: Color) {
    val s = size.minDimension
    val cx = size.width / 2f
    val cy = size.height * 0.84f
    val stroke = Stroke(width = s * 0.11f, cap = StrokeCap.Round)
    for (f in floatArrayOf(0.30f, 0.54f, 0.78f)) {
        val r = s * f
        drawArc(
            color,
            startAngle = 225f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(cx - r, cy - r),
            size = Size(r * 2, r * 2),
            style = stroke,
        )
    }
    drawCircle(color, s * 0.08f, Offset(cx, cy - s * 0.02f))
}

private fun DrawScope.drawBluetooth(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.28f, h * 0.30f)
        lineTo(w * 0.70f, h * 0.68f)
        lineTo(w * 0.50f, h * 0.88f)
        lineTo(w * 0.50f, h * 0.12f)
        lineTo(w * 0.70f, h * 0.32f)
        lineTo(w * 0.28f, h * 0.70f)
    }
    drawPath(path, color, style = Stroke(width = size.minDimension * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawSignal(color: Color) {
    val w = size.width
    val h = size.height
    val bar = w / 5.5f
    for (i in 0 until 4) {
        val barHeight = h * (0.35f + 0.65f * i / 3f)
        drawRoundRect(
            color,
            topLeft = Offset(i * bar * 1.45f, h - barHeight),
            size = Size(bar, barHeight),
            cornerRadius = CornerRadius(bar * 0.3f),
        )
    }
}

private fun DrawScope.drawBattery(color: Color) {
    val w = size.width
    val h = size.height
    val bodyW = w * 0.86f
    val stroke = h * 0.12f
    drawRoundRect(
        color,
        topLeft = Offset(stroke / 2, h * 0.18f),
        size = Size(bodyW - stroke, h * 0.64f),
        cornerRadius = CornerRadius(h * 0.16f),
        style = Stroke(width = stroke),
    )
    drawRoundRect(
        color,
        topLeft = Offset(stroke * 1.6f, h * 0.18f + stroke * 1.1f),
        size = Size((bodyW - stroke * 3.2f) * 0.86f, h * 0.64f - stroke * 2.2f),
        cornerRadius = CornerRadius(h * 0.06f),
    )
    drawRoundRect(
        color,
        topLeft = Offset(bodyW + w * 0.02f, h * 0.36f),
        size = Size(w * 0.1f, h * 0.28f),
        cornerRadius = CornerRadius(h * 0.06f),
    )
}

private fun DrawScope.drawSun(color: Color) {
    val s = size.minDimension
    drawCircle(color, s * 0.2f, center)
    for (i in 0 until 8) {
        val angle = Math.toRadians(i * 45.0)
        val inner = s * 0.31f
        val outer = s * 0.44f
        val cos = kotlin.math.cos(angle).toFloat()
        val sin = kotlin.math.sin(angle).toFloat()
        drawLine(
            color,
            Offset(center.x + cos * inner, center.y + sin * inner),
            Offset(center.x + cos * outer, center.y + sin * outer),
            strokeWidth = s * 0.09f,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawVolume(color: Color) {
    val w = size.width
    val h = size.height
    val path = Path().apply {
        moveTo(w * 0.12f, h * 0.38f)
        lineTo(w * 0.30f, h * 0.38f)
        lineTo(w * 0.52f, h * 0.16f)
        lineTo(w * 0.52f, h * 0.84f)
        lineTo(w * 0.30f, h * 0.62f)
        lineTo(w * 0.12f, h * 0.62f)
        close()
    }
    drawPath(path, color)
    val r = size.minDimension * 0.24f
    drawArc(
        color,
        startAngle = -50f,
        sweepAngle = 100f,
        useCenter = false,
        topLeft = Offset(w * 0.5f - r, h * 0.5f - r),
        size = Size(r * 2, r * 2),
        style = Stroke(width = size.minDimension * 0.09f, cap = StrokeCap.Round),
    )
}

/** A filled circle with a glyph, used for app dots in lists. */
@Composable
internal fun LetterDot(
    letter: String,
    fill: Color,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
) {
    Box(
        modifier = modifier.size(size).clip(CircleShape).background(fill),
        contentAlignment = Alignment.Center,
    ) {
        PText(letter, contentColorFor(fill), scope, size = (size.value * 0.42f).sp, weight = FontWeight.SemiBold)
    }
}

/** A thin progress bar: [track] behind, [group] for the filled part. */
@Composable
internal fun ProgressBar(
    fraction: Float,
    group: ColorGroup,
    track: Color,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
    height: Dp = 6.dp,
) {
    val fill = scope[group]
    Canvas(
        modifier
            .height(height)
            .themed(group, scope, RoundedCornerShape(height / 2)),
    ) {
        val radius = CornerRadius(size.height / 2f)
        drawRoundRect(track, cornerRadius = radius)
        drawRoundRect(fill, size = Size(size.width * fraction.coerceIn(0f, 1f), size.height), cornerRadius = radius)
    }
}

/** A switch drawn in [ColorGroup.SWITCH] (on) or a neutral track (off). */
@Composable
internal fun PreviewSwitch(
    checked: Boolean,
    offTrack: Color,
    scope: PreviewScope,
    modifier: Modifier = Modifier,
) {
    val track = if (checked) scope[ColorGroup.SWITCH] else offTrack
    val thumb = if (checked) contentColorFor(track) else readableOn(Color.White, track, 1.6f)
    Box(
        modifier = modifier
            .size(width = 36.dp, height = 20.dp)
            .fill(ColorGroup.SWITCH, scope, CircleShape, track),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(3.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(thumb),
        )
    }
}
