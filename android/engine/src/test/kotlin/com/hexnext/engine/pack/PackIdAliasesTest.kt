package com.hexnext.engine.pack

import com.hexnext.engine.compile.ThemeExport
import com.hexnext.engine.compile.ThemeMode
import com.hexnext.engine.compile.ThemeSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PackIdAliasesTest {
    @Test
    fun `former ids map to the current id and back`() {
        assertEquals("iolite", PackIdAliases.current("velvet-rope"))
        assertEquals("iolite", PackIdAliases.current("iolite"))
        assertEquals("my-own-pack", PackIdAliases.current("my-own-pack"))
        assertEquals(listOf("velvet-rope"), PackIdAliases.formerIdsOf("iolite"))
        assertEquals(listOf("velvet-rope"), PackIdAliases.formerIdsOf("velvet-rope"))
        assertEquals(listOf("iolite", "velvet-rope"), PackIdAliases.allIdsOf("velvet-rope"))
        assertTrue(PackIdAliases.formerIdsOf("my-own-pack").isEmpty())
        assertTrue(PackIdAliases.isFormer("dusk-and-dawn"))
        assertFalse(PackIdAliases.isFormer("olivine"))
    }

    @Test
    fun `every current id is distinct from every former id`() {
        val current = PackIdAliases.FORMER_TO_CURRENT.values.toSet()
        assertEquals(44, current.size)
        assertTrue(current.none { PackIdAliases.isFormer(it) })
    }

    @Test
    fun `a theme saved with former pack ids loads with the current ones`() {
        val old = """
            {"id":"mine","name":"Mine","source":{"seedArgb":-12345},"mode":"ADVANCED",
             "simplePackId":"velvet-rope",
             "elementPacks":{"navbar":"particle-zoo","qs":"my-own-pack"},
             "elementOptions":{"navbar":{"1nav":"true"}}}
        """.trimIndent()
        val spec = ThemeSpec.fromJson(old)
        assertEquals("iolite", spec.simplePackId)
        assertEquals(mapOf("navbar" to "willemite", "qs" to "my-own-pack"), spec.elementPacks)
        assertEquals(ThemeMode.ADVANCED, spec.mode)
        assertEquals(mapOf("navbar" to mapOf("1nav" to "true")), spec.elementOptions)
        assertEquals(ThemeSpec.FORMAT, spec.format)
        // Written back, the theme carries only current ids.
        assertFalse("velvet-rope" in spec.toJson())
    }

    @Test
    fun `a theme without former ids loads unchanged`() {
        val spec = ThemeSpec.default().copy(simplePackId = "iolite", elementPacks = mapOf("navbar" to "my-own-pack"))
        assertEquals(spec, ThemeSpec.fromJson(spec.toJson()))
    }

    @Test
    fun `an exported theme with a former id imports with the current one`() {
        val spec = ThemeSpec.default().copy(simplePackId = "dusk-and-dawn")
        assertEquals("olivine", ThemeExport.import(ThemeExport.export(spec)).simplePackId)
    }
}
