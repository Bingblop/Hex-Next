package com.hexnext.themer.ui.studio

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hexnext.engine.compile.ColorGroupMatcher
import com.hexnext.engine.compile.OneUiRules
import com.hexnext.engine.compile.ResourceColor
import com.hexnext.engine.compile.ThemeSpec
import com.hexnext.themer.device.DeviceReport
import com.hexnext.themer.theme.SnapshotBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One app the resource editor can open: a scanned One UI package or a SemWT overlay target.
 *
 * @property colors colours the scan counted (APK colours, else SemWT resources); 0 when unknown.
 */
internal data class ResourceTarget(
    val packageName: String,
    val colors: Int,
    val installed: Boolean,
    val debloated: Boolean,
)

/**
 * State of the per-app resource editor.
 *
 * @property hasScan false without a saved scan report; the screen then offers a scan.
 * @property items every colour of [selected] with its group, plan and pin; empty while loading.
 */
internal data class ResourceEditorState(
    val hasScan: Boolean = true,
    val targets: List<ResourceTarget> = emptyList(),
    val selected: String? = null,
    val loading: Boolean = true,
    val items: List<ResourceColor> = emptyList(),
    val error: String? = null,
)

/**
 * Loads one target's colours for [ResourceEditorScreen].
 *
 * Only the selected target's resource table is read (`SnapshotBuilder.build(only = …)`), and the
 * built snapshot is kept, so a spec change (a pin, a group colour) only re-plans the inventory and
 * never reads the disk again. Nothing here writes the spec: pins go through [com.hexnext.themer.ui.MainViewModel].
 */
internal class ResourceEditorModel(app: Application) : AndroidViewModel(app) {

    private val builder = SnapshotBuilder(app)
    private val _state = MutableStateFlow(ResourceEditorState())
    val state: StateFlow<ResourceEditorState> = _state.asStateFlow()

    private var report: DeviceReport? = null
    private var loaded: Pair<String, SnapshotBuilder.Result>? = null
    private var spec: ThemeSpec? = null
    private var job: Job? = null

    init {
        viewModelScope.launch {
            val latest = withContext(Dispatchers.IO) { runCatching { builder.latestReport() }.getOrNull() }
            report = latest
            if (latest == null) {
                _state.value = ResourceEditorState(hasScan = false, loading = false)
                return@launch
            }
            val targets = targetsOf(latest)
            val first = targets.firstOrNull { it.packageName == DEFAULT_TARGET }?.packageName
                ?: targets.firstOrNull()?.packageName
            _state.update { it.copy(hasScan = true, targets = targets, selected = first, loading = first != null) }
            if (first != null) reload()
        }
    }

    /** Opens [packageName]'s colours. */
    fun select(packageName: String) {
        if (_state.value.selected == packageName) return
        _state.update { it.copy(selected = packageName, loading = true, items = emptyList(), error = null) }
        reload()
    }

    /** The spec the planned values follow; called on every committed spec change. */
    fun setSpec(spec: ThemeSpec) {
        if (this.spec == spec) return
        this.spec = spec
        reload()
    }

    /**
     * Drops the cached snapshot (the screen is leaving). The spec is forgotten too, so the next
     * [setSpec] (the screen opened again) reloads even when the theme did not change; otherwise a
     * load cancelled here would leave the list loading forever.
     */
    fun release() {
        job?.cancel()
        job = null
        loaded = null
        spec = null
    }

    private fun reload() {
        val pkg = _state.value.selected ?: return
        val spec = spec ?: return
        val report = report ?: return
        job?.cancel()
        job = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val cached = loaded?.takeIf { it.first == pkg }?.second
                    val built = cached ?: builder.build(report, only = setOf(pkg))
                        ?: error("The scan report could not be read.")
                    loaded = pkg to built
                    val target = built.snapshot.targets[pkg] ?: error("$pkg was not part of the last scan.")
                    ColorGroupMatcher.inventory(
                        target,
                        built.snapshot.systemShades,
                        spec,
                        OneUiRules.forProfile(built.snapshot.oneUi),
                    )
                }
            }
            if (_state.value.selected != pkg) return@launch
            result.fold(
                onSuccess = { items -> _state.update { it.copy(loading = false, items = items, error = null) } },
                onFailure = { e -> _state.update { it.copy(loading = false, items = emptyList(), error = e.message ?: "Could not read $pkg.") } },
            )
        }
    }

    override fun onCleared() {
        release()
    }

    private companion object {
        const val DEFAULT_TARGET = "com.android.settings"

        fun targetsOf(report: DeviceReport): List<ResourceTarget> {
            val out = LinkedHashMap<String, ResourceTarget>()
            for (p in report.packages) {
                out[p.pkg] = ResourceTarget(p.pkg, p.colorCount, p.installed, p.debloated)
            }
            for (s in report.semWt) {
                if (!s.found) continue
                val known = out[s.target]
                out[s.target] = known?.copy(colors = maxOf(known.colors, s.resourceCount))
                    ?: ResourceTarget(s.target, s.resourceCount, installed = true, debloated = false)
            }
            return out.values.sortedWith(compareBy<ResourceTarget>({ !it.installed }, { it.packageName }))
        }
    }
}
