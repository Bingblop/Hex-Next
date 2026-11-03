package com.hexnext.themer.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.themer.ui.MainViewModel
import kotlin.math.roundToInt

/** The element groups of the Quick panel step, in the order the panel shows them. */
internal val QUICK_PANEL_GROUPS: List<ColorGroup> = listOf(
    ColorGroup.QS_PANEL_BG,
    ColorGroup.QS_TILE_ACTIVE,
    ColorGroup.QS_TILE_INACTIVE,
    ColorGroup.SLIDER,
    ColorGroup.NOTIFICATION_BG,
)

/**
 * The Quick panel step (Studio v2 § 2.4): the panel's opacity and a mini panel drawn in the
 * current colours. The QS element rows follow as their own [ElementColorsCard].
 */
@Composable
internal fun QuickPanelCard(viewModel: MainViewModel, spec: ThemeSpec, preview: StudioPreviewState) {
    StudioCard(
        title = "Quick panel",
        eyebrow = "System UI · colour only",
        subtitle = "Tiles, sliders, the panel and notifications take your colours. Samsung's layout and icons stay as they are.",
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary)
            Text(
                "Quick Settings is colour-only on One UI 9. Hex Next never replaces its drawables, so the panel stays stable.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        MiniPanel(preview, dark = true)
        DraftSlider(
            label = "Panel opacity",
            value = spec.qsPanelAlpha.coerceAtLeast(ThemeSpec.MIN_QS_PANEL_ALPHA) * 100f / 255f,
            valueRange = MIN_PANEL_PERCENT..100f,
            format = { "${it.roundToInt()}%" },
            onDraft = { pct -> viewModel.previewDraft(spec.copy(qsPanelAlpha = alphaOf(pct))) },
            onCommit = { pct -> viewModel.setQsPanelAlpha(alphaOf(pct)) },
            caption = "100% keeps Samsung's backdrop. Lower values let the wallpaper show through; 40% is the minimum so text stays readable.",
        )
    }
}

private fun alphaOf(percent: Float): Int = (percent * 255f / 100f).roundToInt().coerceIn(ThemeSpec.MIN_QS_PANEL_ALPHA, 255)

/** Slider floor in percent, matching the lowest alpha the compiler writes. */
private val MIN_PANEL_PERCENT: Float = ThemeSpec.MIN_QS_PANEL_ALPHA * 100f / 255f

/** A strip of the panel in [dark] colours: backdrop at its opacity, two active and two inactive tiles, a slider. */
@Composable
private fun MiniPanel(preview: StudioPreviewState, dark: Boolean) {
    val scheme = preview.scheme(dark)
    val backdrop = Color(scheme.color(ColorGroup.QS_PANEL_BG)).copy(alpha = preview.qsPanelAlpha.coerceIn(0f, 1f))
    val active = Color(scheme.color(ColorGroup.QS_TILE_ACTIVE))
    val inactive = Color(scheme.color(ColorGroup.QS_TILE_INACTIVE))
    val slider = Color(scheme.color(ColorGroup.SLIDER))
    val wallpaper = Color(scheme.color(ColorGroup.ACCENT))
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(wallpaper.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .semantics { contentDescription = "Quick panel sample in your dark colours" },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(backdrop)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(active, active, inactive, inactive).forEach { tile ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(40.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(tile),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(readableOn(tile.toArgb()).copy(alpha = 0.8f)))
                    }
                }
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(inactive),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(0.68f)
                        .height(14.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(slider),
                )
            }
        }
    }
}
