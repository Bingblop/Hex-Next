package com.hexnext.engine.harness

import com.hexnext.engine.arsc.ArscReader
import com.hexnext.engine.arsc.ArscTestBuilder
import com.hexnext.engine.arsc.ArscTestBuilder.Entry
import com.hexnext.engine.compile.*
import com.hexnext.engine.device.OneUiProfile
import com.hexnext.engine.device.OneUiVersion
import com.hexnext.engine.pack.DirectoryPackResolver
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource
import org.junit.Assume
import org.junit.Test
import java.io.File

/**
 * Offline audit of real packs (not part of the normal run: skipped unless `HEX_PACKS` and
 * `HEX_NAMES` are set). Compiles every pack in `HEX_PACKS` (the `packs` branch zips with
 * `index.json`) against One UI 9 targets built from the device lab's name dumps in `HEX_NAMES`
 * (`results/lab-010/dumps/names`), and writes per-target drawable counts to `HEX_OUT` (with
 * `HEX_DETAIL`, every planned drawable and its layers) and each pack's live options with and
 * without the One UI 9 [OptionReach] to `HEX_OUT.options`. `HEX_ONLY` limits the pack ids.
 * See docs/device/all-packs-willemite-wiring-20261105.md.
 */
class PackAuditHarness {
    private fun table(pkg: String, colors: List<String>, drawables: List<String>) = run {
        val b = ArscTestBuilder(pkg)
        b.typeNames += listOf("color", "drawable")
        if (colors.isNotEmpty()) {
            b.typeSpec(1, colors.size)
            b.type(1, colors.size, b.config(), colors.withIndex().associate { (i, n) -> i to Entry.Simple(b.key(n), 0x1c, 0xFF000000.toInt()) })
        }
        if (drawables.isNotEmpty()) {
            b.typeSpec(2, drawables.size)
            b.type(2, drawables.size, b.config(density = 480), drawables.withIndex().associate { (i, n) -> i to Entry.Simple(b.key(n), 0x03, b.string("res/drawable-480/$n.png")) })
        }
        ArscReader.read(b.build())
    }

    @Test fun audit() {
        val packsDir = System.getenv("HEX_PACKS")?.let(::File)
        val names = System.getenv("HEX_NAMES")?.let(::File)
        Assume.assumeTrue(packsDir != null && names != null)
        val pkgs = names!!.listFiles()!!.map { it.name.removePrefix("hexnext-names-").substringBeforeLast('-') }.distinct()
        val targets = pkgs.associateWith { p ->
            fun read(t: String) = File(names, "hexnext-names-$p-$t.txt").takeIf { it.isFile }?.readLines()?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            TargetSnapshot(p, installed = true, table = table(p, read("color"), read("drawable")))
        }
        val shades = ThemeResolver.resolve(ThemeSource(0xFF6750A4.toInt())).light.shades
        val device = DeviceSnapshot(OneUiProfile(OneUiVersion(9, 0, 36, 190000), "SM-S948U1"), targets, shades)
        val resolver = DirectoryPackResolver(packsDir!!)
        val only = System.getenv("HEX_ONLY")?.split(',')
        val out = StringBuilder()
        for (id in resolver.availableIds().filter { only == null || it in only }) {
            val spec = ThemeSpec(id = "t", name = "t", source = ThemeSource(0xFF6750A4.toInt()), simplePackId = id)
            val theme = ThemeCompiler.compile(spec, device, resolver, OneUiRules.load(9)) { 1L }
            for (t in theme.targets) {
                val day = t.drawables.filter { !it.configuration.contains("night") }
                out.append("$id\t${t.packageName}\t${day.size}\t${t.drawables.size}\n")
                if (System.getenv("HEX_DETAIL") != null) for (d in t.drawables) out.append("  ${d.resourceName} ${d.configuration} el=${d.elementId} src=${d.source.category}/${d.source.resourceName} steps=${d.plan.steps}\n")
            }
        }
        File(System.getenv("HEX_OUT") ?: "/tmp/audit.tsv").writeText(out.toString())
        val reach = OptionReach.of(device, OneUiRules.load(9))
        val opts = StringBuilder()
        for (id in resolver.availableIds().filter { only == null || it in only }) {
            val pack = resolver.pack(id) ?: continue
            val all = PackOptions.live(pack)
            val kept = PackOptions.live(pack, reach)
            opts.append("$id\tlive=${all.size}\tone_ui_9=${kept.size}\n")
            for (o in all) if (kept.none { it.option.label == o.option.label }) opts.append("  hidden: ${o.option.label}\n")
            for (o in kept) opts.append("  shown: ${o.option.label} ${o.keys}\n")
        }
        File((System.getenv("HEX_OUT") ?: "/tmp/audit.tsv") + ".options").writeText(opts.toString())
    }
}
