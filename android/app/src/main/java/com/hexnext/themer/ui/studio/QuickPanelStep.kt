package com.hexnext.themer.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
 * The Quick panel step (Studio v2 § 2.4): what can be themed there and the panel's opacity. The
 * live phone preview shows the panel; the QS element rows follow as their own [ElementColorsCard].
 */
@Composable
internal fun QuickPanelCard(viewModel: MainViewModel, spec: ThemeSpec) {
    StudioCard(
        title = "Panel opacity",
        eyebrow = "Quick panel",
        subtitle = "How see-through the pulled-down panel is.",
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
                "Quick Settings is colour-only. Hex Next changes its colours but never its icons, shapes or layout.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        DraftSlider(
            label = "Panel opacity",
            value = spec.qsPanelAlpha.coerceAtLeast(ThemeSpec.MIN_QS_PANEL_ALPHA) * 100f / 255f,
            valueRange = MIN_PANEL_PERCENT..100f,
            format = { "${it.roundToInt()}%" },
            onDraft = { pct -> viewModel.previewDraft(spec.copy(qsPanelAlpha = alphaOf(pct))) },
            onCommit = { pct -> viewModel.setQsPanelAlpha(alphaOf(pct)) },
            caption = "100% keeps Samsung's own see-through level. Lower values make the panel more see-through, down to 40% so text stays readable.",
        )
    }
}

private fun alphaOf(percent: Float): Int = (percent * 255f / 100f).roundToInt().coerceIn(ThemeSpec.MIN_QS_PANEL_ALPHA, 255)

/** Slider floor in percent, matching the lowest alpha the compiler writes. */
private val MIN_PANEL_PERCENT: Float = ThemeSpec.MIN_QS_PANEL_ALPHA * 100f / 255f
