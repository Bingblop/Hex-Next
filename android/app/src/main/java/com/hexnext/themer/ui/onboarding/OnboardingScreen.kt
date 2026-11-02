package com.hexnext.themer.ui.onboarding

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hexnext.themer.device.Compatibility
import com.hexnext.themer.device.CompatibilityCheck
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.theme.InstalledPack
import com.hexnext.themer.ui.EmptyState
import com.hexnext.themer.ui.LocalAppSettings
import com.hexnext.themer.ui.RootGuide
import com.hexnext.themer.ui.SectionCard
import com.hexnext.themer.ui.connectionSummary

/** The five pages of the setup guide, in order. */
private enum class Step(val title: String) {
    WELCOME("Welcome"),
    COMPATIBILITY("Your phone"),
    ACCESS("Root or Shizuku"),
    PACK("Starting pack"),
    DONE("All set"),
}

/**
 * The first-launch setup guide, also reachable from App settings > About > Setup guide:
 * Welcome → Compatibility → Root or Shizuku → Pick a starting pack → Done. Every page can be
 * skipped; Back steps through the pages and leaves on the first one. [onFinish] is called for
 * Done and Skip alike, so the host marks the guide as seen either way.
 *
 * @param shizuku the live connection state, so the access page updates as the user acts.
 * @param rootToolLinks the newest APK links of the root tools, as the Connection card shows them.
 * @param packs the installed packs to pick from; an empty list shows how to get some.
 * @param selectedPackId the editor's current pack (null = colours only).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    shizuku: ShizukuState,
    versionLine: String,
    rootToolLinks: Map<String, String>,
    packs: List<InstalledPack>,
    selectedPackId: String?,
    onSelectPack: (String?) -> Unit,
    onRequestPermission: () -> Unit,
    onFinish: () -> Unit,
) {
    var index by rememberSaveable { mutableIntStateOf(0) }
    val steps = Step.entries
    val step = steps[index.coerceIn(0, steps.lastIndex)]
    val last = index == steps.lastIndex
    val reduceMotion = LocalAppSettings.current.reduceMotion

    fun back() {
        if (index > 0) index-- else onFinish()
    }
    BackHandler(onBack = ::back)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Set up Hex Next", fontWeight = FontWeight.SemiBold) },
                actions = {
                    if (!last) TextButton(onClick = onFinish) { Text("Skip") }
                },
            )
        },
        bottomBar = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StepDots(count = steps.size, current = index)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (index > 0) {
                        OutlinedButton(onClick = ::back, modifier = Modifier.height(52.dp)) { Text("Back") }
                    }
                    Button(
                        onClick = { if (last) onFinish() else index++ },
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) { Text(if (last) "Done" else "Next") }
                }
            }
        },
    ) { padding ->
        val page: @Composable (Step) -> Unit = { current ->
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (current) {
                    Step.WELCOME -> WelcomePage()
                    Step.COMPATIBILITY -> CompatibilityPage(versionLine)
                    Step.ACCESS -> AccessPage(shizuku, rootToolLinks, onRequestPermission)
                    Step.PACK -> PackPage(packs, selectedPackId, onSelectPack)
                    Step.DONE -> DonePage(shizuku, packs, selectedPackId)
                }
            }
        }
        Box(Modifier.padding(padding)) {
            if (reduceMotion) {
                page(step)
            } else {
                Crossfade(targetState = step, label = "onboarding") { current -> page(current) }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------------------------

@Composable
private fun WelcomePage() {
    Hero(
        icon = Icons.Outlined.Star,
        title = "Make One UI yours",
        body = "Hex Next changes the colours, icons, backgrounds and font of your Samsung phone. " +
            "Pick a look, preview it, and apply it in one tap. This short guide gets you ready.",
    )
    SectionCard("What you'll need") {
        Bullet("A Samsung phone on One UI 7 or later.")
        Bullet("Root access, or Shizuku started with root. The next pages show the easiest way.")
        Bullet("A theme pack, or just your favourite colour.")
    }
}

@Composable
private fun CompatibilityPage(versionLine: String) {
    val uriHandler = LocalUriHandler.current
    when (val compat = CompatibilityCheck.check()) {
        Compatibility.Supported -> {
            Hero(
                icon = Icons.Outlined.CheckCircle,
                title = "Your phone is supported",
                body = "$versionLine. Everything Hex Next does works on this One UI version.",
            )
            SectionCard("Good to know") {
                Bullet("Quick Settings keeps Samsung's layout and only takes your colours, so it stays usable on every One UI version.")
                Bullet("Apps you've removed from the phone are skipped automatically.")
            }
        }
        is Compatibility.TooOld -> {
            Hero(
                icon = Icons.Outlined.Warning,
                title = "This phone is too old for Hex Next",
                body = "Hex Next needs Android 15 and One UI 7 or later. This phone runs Android " +
                    "${Build.VERSION.RELEASE} with ${compat.oneUi ?: "an unknown One UI version"}.",
            )
            SectionCard("Older phones") {
                Text(
                    "The Hex Installer app supports Android 14 and earlier.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(onClick = { uriHandler.openUri(CompatibilityCheck.HEX_INSTALLER_URL) }) { Text("Get Hex Installer") }
            }
        }
        Compatibility.NotOneUi -> Hero(
            icon = Icons.Outlined.Warning,
            title = "Not a One UI phone",
            body = "Hex Next themes Samsung One UI only. This phone is not running One UI.",
        )
    }
}

@Composable
private fun AccessPage(shizuku: ShizukuState, links: Map<String, String>, onRequestPermission: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val ready = shizuku as? ShizukuState.Ready
    val connected = ready?.isPrivileged == true
    Hero(
        icon = if (connected) Icons.Outlined.CheckCircle else Icons.Outlined.Lock,
        title = if (connected) "Connected" else "Give Hex Next access",
        body = connectionSummary(shizuku),
    )
    when (shizuku) {
        ShizukuState.PermissionNeeded -> SectionCard("Shizuku") {
            Text("Shizuku is running. Allow Hex Next to use it.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onRequestPermission) { Text("Allow access") }
        }
        ShizukuState.NotRunning, ShizukuState.TooOld -> SectionCard("Shizuku") {
            Text(
                "Already rooted? Install Shizuku and start it with root, then come back here.",
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = { uriHandler.openUri("https://shizuku.rikka.app/download/") }) { Text("Get Shizuku") }
        }
        else -> Unit
    }
    if (!connected) {
        SectionCard("No root yet?") {
            RootGuide(links, startExpanded = true)
        }
    } else {
        SectionCard("You're ready") {
            Text(
                "Hex Next can now build and apply overlays. You can change this later from the Connection card on the main screen.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun PackPage(packs: List<InstalledPack>, selectedPackId: String?, onSelectPack: (String?) -> Unit) {
    Hero(
        icon = Icons.Outlined.Star,
        title = "Pick a starting pack",
        body = "A pack brings icons and other artwork. Colours only keeps Samsung's artwork and recolours it. " +
            "You can change this any time.",
    )
    SectionCard("Theme pack") {
        ChoiceRow(selected = selectedPackId == null, onClick = { onSelectPack(null) }) {
            Column(Modifier.weight(1f)) {
                Text("Colours only", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    "Just your accent colour, everywhere.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        packs.forEach { pack ->
            ChoiceRow(selected = selectedPackId == pack.id, onClick = { onSelectPack(pack.id) }) {
                Column(Modifier.weight(1f)) {
                    Text(pack.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        listOfNotNull(
                            pack.author.takeIf { it.isNotBlank() },
                            "${pack.drawableCount} drawables",
                            if (pack.hasSettingsIcons) "Settings icons" else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (packs.isEmpty()) {
            EmptyState(
                icon = Icons.Outlined.Info,
                text = "No packs installed yet. Use \"Get packs\" on the main screen to download some, or import a pack file.",
            )
        }
    }
}

@Composable
private fun DonePage(shizuku: ShizukuState, packs: List<InstalledPack>, selectedPackId: String?) {
    val connected = (shizuku as? ShizukuState.Ready)?.isPrivileged == true
    val packName = packs.firstOrNull { it.id == selectedPackId }?.name ?: "Colours only"
    Hero(
        icon = Icons.Outlined.CheckCircle,
        title = "All set",
        body = "Pick an accent colour, preview the result and tap Apply theme. " +
            "The gear icon at the top opens App settings, including this guide.",
    )
    SectionCard("Summary") {
        SummaryRow("Access", if (connected) "Connected" else "Not connected yet")
        SummaryRow("Theme pack", packName)
        if (!connected) {
            Text(
                "You can still prepare your theme now and apply it once root or Shizuku is ready.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------------------------

/** A large icon, a headline and one paragraph: the top of every page. */
@Composable
private fun Hero(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun Bullet(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One radio row, written like the editor's pack rows. */
@Composable
private fun ChoiceRow(selected: Boolean, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        content()
    }
}

/** The page indicator: one dot per step, the current one wider and tinted. */
@Composable
private fun StepDots(count: Int, current: Int) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            val active = i == current
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .height(8.dp)
                    .width(if (active) 20.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
