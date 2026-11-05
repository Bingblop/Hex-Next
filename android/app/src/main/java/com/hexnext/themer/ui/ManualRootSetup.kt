package com.hexnext.themer.ui

import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hexnext.themer.ServiceTasks
import com.hexnext.themer.device.RootSetupOptions
import com.hexnext.themer.device.RootTools
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.shizuku.SystemLauncher
import com.hexnext.themer.ui.settings.rememberSafeUriHandler

/** Shared by onboarding and Connection. Returning from the installer refreshes passive checks. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ManualRootSetup(state: ShizukuState, startExpanded: Boolean = false) {
    val context = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { revision++ }
    // DirtyFrag retains DFRoot's package ID. Do not mislabel a legacy installation.
    val legacyHelper = remember(context, revision) {
        runCatching {
            val app = context.packageManager.getApplicationInfo(
                RootTools.DIRTYFRAG.packageName, PackageManager.ApplicationInfoFlags.of(0),
            )
            app.loadLabel(context.packageManager).toString().equals("DFRoot", ignoreCase = true)
        }.getOrDefault(false)
    }
    val installed = remember(context, revision, legacyHelper) {
        RootTools.ALL.filter { tool ->
            if (legacyHelper && tool == RootTools.DIRTYFRAG) return@filter false
            try {
                context.packageManager.getApplicationInfo(tool.packageName, PackageManager.ApplicationInfoFlags.of(0))
                true
            } catch (_: PackageManager.NameNotFoundException) { false }
              catch (_: SecurityException) { true } // Unknown visibility is not proof of absence.
        }.mapTo(hashSetOf()) { it.packageName }
    }
    val privileged = (state as? ShizukuState.Ready)?.let { it.isPrivileged && !it.viaSu } == true
    val options = RootSetupOptions(privileged, SystemLauncher.suPresent(), installed)
    val request by ShizukuManager.rootRequest.collectAsStateWithLifecycle()
    val task by ServiceTasks.running.collectAsStateWithLifecycle()
    if (privileged) return
    fun open(tool: RootTools.Tool) {
        val intent = context.packageManager.getLaunchIntentForPackage(tool.packageName)
        if (intent == null) {
            Toast.makeText(context, "${tool.name} cannot be opened. It may be disabled.", Toast.LENGTH_LONG).show()
        } else {
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(context, "${tool.name} could not be opened.", Toast.LENGTH_LONG).show()
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (legacyHelper && !options.hasRootSetup) {
            Text("The older DFRoot app uses DirtyFrag's package name. Review the DirtyFrag release " +
                "to replace it manually; Hex Next will not uninstall it for you.", style = MaterialTheme.typography.bodySmall)
        }
        if (options.offerRootRequest) {
            Text(
                "Request root access for Hex Next from your existing root manager. Hex Next will try " +
                    "UID 1000 first, then UID 0 if necessary. UID 0 overlays may disappear after reboot. " +
                    "An installed manager alone does not verify access.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = ShizukuManager::requestRootAccess, enabled = !request.requesting && task == null) {
                    Text(if (request.requesting) "Requesting root access…" else "Request root access")
                }
                if (RootTools.KERNELSU.packageName in installed) {
                    OutlinedButton(onClick = { open(RootTools.KERNELSU) }, enabled = !request.requesting) {
                        Text("Open KernelSU")
                    }
                }
            }
            if (!request.requesting) request.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (!options.hasRootSetup) Text(
                "DirtyFrag access is temporary and ends after a full reboot. UID 1000 theme overlays remain applied; " +
                    "UID 0 fallback overlays do not have reboot persistence. If access is not active yet, complete the setup below.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (options.offerOpenDirtyFrag) {
            OutlinedButton(onClick = { open(RootTools.DIRTYFRAG) }, enabled = !request.requesting) { Text("Open DirtyFrag") }
        }
        if (!options.hasRootSetup) RootGuide(options.missingTools, startExpanded)
    }
}

/** A deliberate handoff to a browser, not an in-app APK download or silent install. */
@Composable
internal fun ManualRootDownload(tool: RootTools.Tool) {
    val uri = rememberSafeUriHandler()
    var confirm by rememberSaveable(tool.packageName) { androidx.compose.runtime.mutableStateOf(false) }
    OutlinedButton(onClick = { confirm = true }) { Text("Download and install ${tool.name}") }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Manually install ${tool.name}?") },
        text = {
            Text(
                "Open ${tool.releasesUrl} in your browser, review the release, download its APK, " +
                    "and choose whether to install it in Android. Only allow installation from a source you trust. " +
                    "Rooting can affect device security and stability; compatibility is not guaranteed. " +
                    "Hex Next will not install it, run DirtyFrag, or restart your phone.",
            )
        },
        confirmButton = { TextButton(onClick = { confirm = false; uri.openUri(tool.releasesUrl) }) { Text("Open upstream releases") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } },
    )
}
