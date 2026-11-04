package com.hexnext.themer.autotest

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hexnext.themer.ServiceTasks
import com.hexnext.themer.device.Compatibility
import com.hexnext.themer.device.CompatibilityCheck
import com.hexnext.themer.shizuku.ShizukuManager
import com.hexnext.themer.shizuku.ShizukuState
import com.hexnext.themer.ui.DiagnosticsViewModel
import com.hexnext.themer.ui.HexNextTheme
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.preview.BarePreviewScreen
import com.hexnext.themer.ui.settings.AppSettingsStore
import com.hexnext.themer.ui.studio.PreviewTab
import com.hexnext.themer.ui.studio.StudioTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Owner build only: the hook the phone-side test runner (tools/device/autotest.sh) drives, so a
 * build can be installed, scanned, applied and compared with its live preview without a tap.
 *
 * Not exported: only root or the shell (`su -c am start -n ...`) can start it. Commands, in the
 * `cmd` extra:
 *  - `preview` (`tab` = a [PreviewTab] name, `dark` = boolean): draws the live phone preview of
 *    the stored theme full screen, for a screenshot. Writes `autotest/preview.done` once drawn.
 *  - `scan`: runs the Diagnostics phone scan. Writes `autotest/scan.done`.
 *  - `apply` (`confirm` = boolean, default false): applies the stored theme (`files/theme.json`)
 *    exactly as the Apply button does, then optionally confirms it. Writes `autotest/apply.done`.
 *  - `remove`: turns the Hex Next theme off. Writes `autotest/remove.done`.
 * Each `.done` file is JSON with `ok` and `detail`; the runner deletes it before each command.
 */
class AutotestActivity : ComponentActivity() {

    private val main: MainViewModel by viewModels()
    private val diagnostics: DiagnosticsViewModel by viewModels()

    private val outDir: File get() = File(filesDir, "autotest").apply { mkdirs() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val supported = Build.MANUFACTURER.equals("samsung", ignoreCase = true) &&
            CompatibilityCheck.check() == Compatibility.Supported
        val cmd = intent.getStringExtra("cmd").orEmpty()
        if (!supported) {
            done(cmd.ifEmpty { "unknown" }, false, "unsupported phone")
            finish()
            return
        }
        ShizukuManager.init()
        when (cmd) {
            "preview" -> showPreview()
            "scan", "apply", "remove" -> runServiceCommand(cmd)
            "pack" -> selectPack(intent.getStringExtra("id").orEmpty())
            else -> {
                done("unknown", false, "unknown cmd '$cmd'")
                finish()
            }
        }
    }

    /**
     * `pack` (`id`): selects pack [id] for the next apply, downloading it from the catalogue first
     * when it is not on the phone, as the Skins gallery does. Writes `autotest/pack.done`.
     */
    private fun selectPack(id: String) {
        lifecycleScope.launch {
            main.awaitReadyToApply()
            if (main.ui.value.packs.none { it.id == id }) {
                main.loadCatalogue()
                val entry = withTimeoutOrNull(60_000) {
                    main.ui.first { !it.catalogue.loading && (it.catalogue.entries.isNotEmpty() || it.catalogue.error != null) }
                }?.catalogue?.entries?.firstOrNull { it.id == id }
                if (entry == null) {
                    done("pack", false, "pack $id not in the catalogue (${main.ui.value.catalogue.error})")
                    finish()
                    return@launch
                }
                main.downloadPack(entry, selectWhenDone = true)
                withTimeoutOrNull(5 * 60_000) { main.ui.first { id !in it.catalogue.downloading && !it.busy && it.packs.any { p -> p.id == id } } }
                delay(500)
            }
            main.selectPack(id)
            delay(1_500)
            val ok = main.ui.value.spec.simplePackId == id
            done("pack", ok, "selected=${main.ui.value.spec.simplePackId}")
            finish()
        }
    }

    private fun showPreview() {
        val tab = runCatching { PreviewTab.valueOf(intent.getStringExtra("tab").orEmpty()) }.getOrDefault(PreviewTab.SETTINGS)
        val dark = intent.getBooleanExtra("dark", true)
        // Icons and the compiled colours of the requested mode, as the Studio's dark switch does.
        main.setPreviewDark(dark)
        main.refreshPreview()
        enableEdgeToEdge()
        // The preview screens draw their own status row: hide the real bars so they do not overlap.
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        val settingsStore = AppSettingsStore.get(this)
        setContent {
            val settings by settingsStore.settings.collectAsStateWithLifecycle()
            val state by main.studioPreview.collectAsStateWithLifecycle()
            HexNextTheme(settings) {
                StudioTheme {
                    // Bare: the screen alone at the real screen's size, for a 1:1 comparison.
                    BarePreviewScreen(state, tab, dark, Modifier.fillMaxSize().background(Color.Black))
                }
            }
            // Icons come from the compile preview: wait until they have loaded, then give the
            // colour animation time to settle before the runner takes its screenshot.
            LaunchedEffect(Unit) {
                withTimeoutOrNull(30_000) { main.studioPreview.first { !it.iconsLoading } }
                delay(1_500)
                done("preview", true, "tab=${tab.name} dark=$dark")
            }
        }
    }

    private fun runServiceCommand(cmd: String) {
        // A plain status screen, so the phone does not look frozen during a long scan or apply.
        setContent {
            Box(Modifier.fillMaxSize().background(Color(0xFF0B1220)), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Text(
                    "Hex Next device lab\nRunning: $cmd\nLeave the phone alone until it finishes.",
                    color = Color(0xFF7DE3F4),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
        lifecycleScope.launch {
            // Shizuku hands over its binder shortly after start; the startup safety check runs too.
            val ready = withTimeoutOrNull(60_000) { ShizukuManager.state.first { it is ShizukuState.Ready } }
            if (ready == null) {
                done(cmd, false, "Shizuku not ready: ${ShizukuManager.state.value}")
                finish()
                return@launch
            }
            withTimeoutOrNull(120_000) { ServiceTasks.running.first { it == null } }
            when (cmd) {
                "scan" -> {
                    diagnostics.runScan()
                    awaitIdle { diagnostics.state.value.busy }
                    val report = diagnostics.state.value.report
                    done(cmd, report != null, report?.let { "${it.model} ${it.oneUiVersion}" } ?: "no report")
                }
                "apply" -> {
                    val asked = System.currentTimeMillis()
                    main.awaitReadyToApply()
                    main.applyTheme()
                    awaitIdle { main.ui.value.busy }
                    // The saved report, not the screen state (lab-020/022 read a stale one): an
                    // apply that started before this command is not this apply's.
                    val report = (savedReport() ?: main.ui.value.lastApply)?.takeIf { it.startedAt >= asked - 1_000 }
                    val ok = report?.success == true
                    // What the Editor log showed (a refusal says why there).
                    runCatching { File(outDir, "apply-ui-log.txt").writeText(main.log.value.joinToString("\n") { "${it.level} ${it.text}" }) }
                    if (ok && intent.getBooleanExtra("confirm", false)) main.confirmWorks()
                    done(cmd, ok, report?.let { "status=${it.status} error=${it.error} pack=${main.ui.value.spec.simplePackId}" } ?: "no apply report (refused or not started; pack=${main.ui.value.spec.simplePackId})")
                }
                "remove" -> {
                    main.removeTheme()
                    awaitIdle { main.ui.value.busy }
                    done(cmd, true, "removed")
                }
            }
            finish()
        }
    }

    /**
     * Waits for a task to start (up to 5 s) and then to finish (up to 15 min). Every 5 s it notes
     * which task holds the service in autotest/progress.txt (the lab collects it).
     */
    private suspend fun awaitIdle(busy: () -> Boolean) {
        withTimeoutOrNull(5_000) { while (!busy() && ServiceTasks.running.value == null) delay(100) }
        val progress = File(outDir, "progress.txt").apply { writeText("") }
        var tick = 0
        withTimeoutOrNull(15 * 60_000) {
            while (busy() || ServiceTasks.running.value != null) {
                if (tick++ % 20 == 0) runCatching {
                    progress.appendText("${System.currentTimeMillis()} busy=${busy()} task=${ServiceTasks.running.value}\n")
                }
                delay(250)
            }
        }
    }

    private fun savedReport(): com.hexnext.engine.compile.ApplyReport? = runCatching {
        com.hexnext.engine.compile.ApplyReport.fromJson(com.hexnext.themer.theme.ThemeApplier.reportFile(this).readText())
    }.getOrNull()

    private fun done(cmd: String, ok: Boolean, detail: String) {
        val json = JSONObject().put("cmd", cmd).put("ok", ok).put("detail", detail).put("at", System.currentTimeMillis())
        File(outDir, "$cmd.done").writeText(json.toString())
    }
}
