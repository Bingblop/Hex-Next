package com.hexnext.engine.compile

import com.hexnext.engine.arsc.ArscReader
import com.hexnext.engine.arsc.ArscTestBuilder
import com.hexnext.engine.arsc.ArscTestBuilder.Entry
import com.hexnext.engine.arsc.ResourceTable
import com.hexnext.engine.device.OneUiProfile
import com.hexnext.engine.device.OneUiVersion
import com.hexnext.engine.frro.FabricatedOverlayFile
import com.hexnext.engine.frro.FrroReader
import com.hexnext.engine.frro.FrroWriter
import com.hexnext.engine.pack.Pack
import com.hexnext.engine.pack.PackLayer
import com.hexnext.engine.pack.Tint
import com.hexnext.engine.palette.ThemeResolver
import com.hexnext.engine.palette.ThemeSource

internal object Fixtures {
    /** Palette active on the "device" at scan time. */
    val reference: Map<String, Int> = ThemeResolver.resolve(ThemeSource(0xFF6750A4.toInt())).light.shades

    val profile9 = OneUiProfile(OneUiVersion(9, 0, 36, 190000), "SM-S938B")

    /**
     * A synthetic table: [colors] in the default config; [drawables] name → densities
     * (0 = default config), each written as a `res/...png` file reference.
     */
    fun table(pkg: String, colors: List<String>, drawables: Map<String, List<Int>> = emptyMap()): ResourceTable {
        val b = ArscTestBuilder(pkg)
        b.typeNames += listOf("color", "drawable")
        if (colors.isNotEmpty()) {
            b.typeSpec(1, colors.size)
            b.type(1, colors.size, b.config(), colors.withIndex().associate { (i, n) -> i to Entry.Simple(b.key(n), 0x1c, 0xFF000000.toInt()) })
        }
        if (drawables.isNotEmpty()) {
            val names = drawables.keys.toList()
            b.typeSpec(2, names.size)
            val densities = drawables.values.flatten().distinct().sorted()
            for (d in densities) {
                val entries = names.withIndex().filter { d in drawables.getValue(it.value) }.associate { (i, n) ->
                    i to Entry.Simple(b.key(n), 0x03, b.string("res/drawable-$d/$n.png"))
                }
                b.type(2, names.size, b.config(density = d), entries)
            }
        }
        return ArscReader.read(b.build())
    }

    /** A SemWT overlay written with [FrroWriter] and parsed back with [FrroReader]. */
    fun semwt(pkg: String, colors: List<Triple<String, Int, String>>): FabricatedOverlayFile {
        val w = FrroWriter("android", "SemWT_$pkg", pkg)
        for ((name, argb, config) in colors) w.setColor("color/$name", argb, config)
        return FrroReader.read(w.toByteArray())
    }

    fun ref(key: String): Int = reference.getValue(key)

    fun layer(category: String, stage: String, tint: Tint, resources: List<String>, pass: String = "once") =
        PackLayer(pass, stage, category, tint, "layers/$pass/$stage/${tint.slot ?: tint.argb ?: "default"}/$category", resources)

    fun pack(id: String, layers: List<PackLayer>, palette: Map<String, String> = emptyMap()) =
        Pack(id = id, name = id, palette = palette, layers = layers)
}
