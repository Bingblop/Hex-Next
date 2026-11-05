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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hexnext.engine.compile.ColorGroup
import com.hexnext.engine.compile.ShapeControls
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.engine.compile.ValueControl
import com.hexnext.engine.compile.ValueKind
import com.hexnext.engine.compile.ValueSetting
import com.hexnext.themer.theme.ThemeApplier
import com.hexnext.themer.ui.MainViewModel
import kotlin.math.roundToInt

/**
 * The element groups of the Quick panel step, in the order the panel shows them. The panel's
 * brightness and volume sliders are drawn in the tile colours on One UI 9, so [ColorGroup.SLIDER]
 * (seek bars in apps and the volume popup) stays under Controls.
 */
internal val QUICK_PANEL_GROUPS: List<ColorGroup> = listOf(
    ColorGroup.QS_PANEL_BG,
    ColorGroup.QS_TILE_ACTIVE,
    ColorGroup.QS_TILE_INACTIVE,
    ColorGroup.QS_TILE_ICON_ON,
    ColorGroup.QS_TILE_ICON_OFF,
    ColorGroup.QS_TEXT,
    ColorGroup.NOTIFICATION_BG,
)

/**
 * The Quick panel step (Studio v2 § 2.4): the panel's opacity and its shapes (tile and panel
 * corners, slider thickness, notification corners). The live phone preview shows the panel; the
 * QS element rows follow as their own [ElementColorsCard].
 */
@Composable
internal fun QuickPanelCard(viewModel: MainViewModel, spec: ThemeSpec) {
    StudioCard(
        title = "Panel opacity",
        eyebrow = "Quick panel",
        subtitle = "How see-through the pulled-down panel is.",
    ) {
        InfoNote(
            "Hex Next changes the Quick panel's colours, opacity and a few sizes that One UI defines " +
                "itself. It never replaces its icons or changes its layout.",
        )
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
    QuickPanelShapesCard(viewModel, spec)
}

/**
 * The shape controls. Each resolves through the One UI rules ([ValuePlanner.availableControls]):
 * a control whose resource names this phone does not define is hidden, one whose scan predates the
 * size lists shows "Rescan needed", and only names the scan found are ever written.
 */
@Composable
private fun QuickPanelShapesCard(viewModel: MainViewModel, spec: ThemeSpec) {
    val controls by viewModel.valueControls.collectAsState()
    val context = LocalContext.current
    LaunchedEffect(Unit) { viewModel.loadGroupCounts() }
    val shown = SHAPE_LABELS.keys.mapNotNull { id -> controls[id]?.takeIf { it.available || !it.scanned } }
    StudioCard(
        title = "Shapes",
        eyebrow = "Quick panel",
        subtitle = "Corner roundness and slider thickness, in One UI's own sizes.",
        trailing = if (spec.shapes.isNotEmpty()) {
            { TextButton(onClick = { viewModel.resetShapes() }) { Text("Reset") } }
        } else {
            null
        },
    ) {
        if (controls.isEmpty()) {
            InfoNote("Rescan needed: scan this phone first, so Hex Next knows which sizes its One UI defines.")
            return@StudioCard
        }
        if (shown.isEmpty()) {
            InfoNote("This One UI version defines none of the Quick panel sizes Hex Next knows, so there is nothing to change here.")
            return@StudioCard
        }
        val quarantined = remember(spec.shapes) { ThemeApplier.isValuesQuarantined(context, spec) }
        if (quarantined) {
            InfoNote("System UI restarted repeatedly with these sizes, so Hex Next switched them off. Change or reset them to try again.")
        }
        for (control in shown) {
            ShapeSlider(viewModel, spec, control)
        }
        Text(
            "Sizes go in their own overlay and need Shizuku running as root. If System UI restarts " +
                "repeatedly after an apply, Hex Next turns the sizes off first and keeps the colours.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ShapeSlider(viewModel: MainViewModel, spec: ThemeSpec, control: ValueControl) {
    val label = SHAPE_LABELS[control.id] ?: control.id
    if (!control.scanned) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("Rescan needed", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    val rule = control.rule
    val min = (rule.min ?: 0.0).toFloat()
    val max = (rule.max ?: maxOf(min.toDouble(), control.stock ?: 0.0) + 48.0).toFloat()
    val step = rule.step.toFloat().coerceAtLeast(0.5f)
    val set = spec.shapes[control.id]?.takeIf { it.kind == ValueKind.DIMEN }?.number?.toFloat()
    val stock = control.stock?.toFloat()
    val value = (set ?: stock ?: min).coerceIn(min, max)
    val unit = rule.unit.suffix
    fun snap(v: Float): Double = ((v / step).roundToInt() * step).toDouble()
    fun withShape(v: Float) = spec.copy(shapes = spec.shapes + (control.id to ValueSetting.dimen(snap(v), rule.unit)))
    DraftSlider(
        label = label,
        value = value,
        valueRange = min..max,
        steps = (((max - min) / step).roundToInt() - 1).coerceAtLeast(0),
        format = { v -> "${formatSize(snap(v))} $unit" + if (set == null && stock != null) " (One UI)" else "" },
        onDraft = { v -> viewModel.previewDraft(withShape(v)) },
        onCommit = { v ->
            val target = snap(v)
            // Back at One UI's own size: nothing to write.
            if (stock != null && kotlin.math.abs(target - stock) < 0.01) viewModel.setShape(control.id, null)
            else viewModel.setShape(control.id, ValueSetting.dimen(target, rule.unit))
        },
        caption = if (set != null && stock != null) "One UI: ${formatSize(stock.toDouble())} $unit" else null,
    )
}

private fun formatSize(v: Double): String = if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

@Composable
private fun InfoNote(text: String) {
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
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** The shape controls shown, in order, with their labels. */
private val SHAPE_LABELS: Map<String, String> = linkedMapOf(
    ShapeControls.TILE_CORNER to "Tile corners",
    ShapeControls.PANEL_CORNER to "Panel corners",
    ShapeControls.SLIDER_THICKNESS to "Slider thickness",
    ShapeControls.NOTIFICATION_CORNER to "Notification corners",
)

private fun alphaOf(percent: Float): Int = (percent * 255f / 100f).roundToInt().coerceIn(ThemeSpec.MIN_QS_PANEL_ALPHA, 255)

/** Slider floor in percent, matching the lowest alpha the compiler writes. */
private val MIN_PANEL_PERCENT: Float = ThemeSpec.MIN_QS_PANEL_ALPHA * 100f / 255f
