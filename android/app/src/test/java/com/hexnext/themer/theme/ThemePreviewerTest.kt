package com.hexnext.themer.theme

import com.hexnext.engine.compile.PlannedDrawableJob
import com.hexnext.engine.map.DrawableSource
import com.hexnext.engine.pack.CompositePlan
import org.junit.Assert.assertSame
import org.junit.Test

class ThemePreviewerTest {
    @Test
    fun `best drawable follows requested mode and falls back when absent`() {
        val day = job("xxhdpi")
        val night = job("night-xxhdpi")

        assertSame(day, ThemePreviewer.bestOf(listOf(day, night)))
        assertSame(night, ThemePreviewer.bestOf(listOf(day, night), night = true))
        assertSame(night, ThemePreviewer.bestOf(listOf(night)))
    }

    private fun job(configuration: String) = PlannedDrawableJob(
        resourceName = "com.android.settings:drawable/sec_ic_settings_display",
        configuration = configuration,
        plan = CompositePlan(emptyList()),
        ninePatch = false,
        source = DrawableSource("pack", "settings_icons", "display"),
        elementId = "settings_icons",
    )
}
