package com.hexnext.themer.ui.studio

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hexnext.themer.ui.HexNextTheme
import com.hexnext.themer.ui.MainViewModel
import com.hexnext.themer.ui.settings.AppSettings
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog

/**
 * Draws the shell's pages on the JVM and writes them to `build/shell-shots/<name>.png`. Not an
 * assertion suite: a way to look at every page of the navigation without a phone, so truncated
 * labels, overlaps and contrast problems show before a build reaches a tester. Run with
 * `./gradlew :app:testOwnerDebugUnitTest --tests '*ShellScreenshotTest*'` and open the PNGs.
 *
 * It only asserts that each page composes and that the controls it taps exist, so it also acts
 * as a smoke test of the shell in CI. The pack tests need the bundled debug packs
 * (`src/debug/assets/packs/`, copied in by the workflow) and skip without them. Robolectric
 * fetches its `android-all` jar on the first run (about 100 MB, cached afterwards).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class, qualifiers = "w360dp-h780dp-night-xhdpi")
class ShellScreenshotTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private fun shots(): File = File(System.getProperty("user.dir"), "build/shell-shots").apply { mkdirs() }

    private var activity: ComponentActivity? = null
    private lateinit var viewModel: MainViewModel

    /**
     * Draws every window (the activity and any open sheet) into one bitmap through View.draw:
     * Robolectric has no PixelCopy, so captureToImage cannot be used here.
     */
    private fun save(name: String) {
        compose.waitForIdle()
        val views = windowViews().ifEmpty { listOfNotNull(activity?.window?.decorView) }
        val width = views.maxOf { it.width.takeIf { w -> w > 0 } ?: 720 }
        val height = views.maxOf { it.height.takeIf { h -> h > 0 } ?: 1560 }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        views.forEach { view ->
            if (view.width == 0 || view.height == 0) {
                view.measure(
                    View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                view.layout(0, 0, width, height)
            }
            view.draw(canvas)
        }
        File(shots(), "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** The root views of every window, in z order, through WindowManagerGlobal. */
    @Suppress("UNCHECKED_CAST")
    private fun windowViews(): List<View> = runCatching {
        val global = Class.forName("android.view.WindowManagerGlobal")
        val instance = global.getMethod("getInstance").invoke(null)
        val field = global.getDeclaredField("mViews").apply { isAccessible = true }
        (field.get(instance) as List<View>).toList()
    }.getOrDefault(emptyList())

    private fun launch(destination: String? = null): ComponentActivity {
        ShadowLog.stream = System.out
        val app = ApplicationProvider.getApplicationContext<Application>()
        val intent = Intent(app, ComponentActivity::class.java)
        if (destination != null) intent.putExtra(EXTRA_DESTINATION, destination)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java, intent)
        val activity = controller.get()
        this.activity = activity
        val viewModel = MainViewModel(app, SavedStateHandle())
        this.viewModel = viewModel
        activity.setContent {
            val ui by viewModel.ui.collectAsStateWithLifecycle()
            val shizuku by viewModel.shizuku.collectAsStateWithLifecycle()
            HexNextTheme(AppSettings()) {
                StudioTheme { StudioScreen(viewModel, ui, shizuku) }
            }
        }
        controller.setup()
        compose.waitForIdle()
        return activity
    }

    /** The sub-tab named [label] (the phone's own tab row may carry the same word). */
    private fun subTab(label: String) = compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("subtabs")))

    /** Swipes the page up by about half a screen. */
    private fun swipeUp() {
        compose.onRoot().performTouchInput { swipeUp(startY = bottom * 0.75f, endY = bottom * 0.3f, durationMillis = 300) }
        compose.waitForIdle()
    }

    /** Scrolls the page list until the item holding [text] is on screen (lazy items off screen are not composed). */
    private fun scrollTo(text: String) {
        compose.onNode(hasTestTag("page")).performScrollToNode(hasText(text, substring = true))
        compose.waitForIdle()
    }

    @Test
    fun theme() {
        launch()
        save("theme-palette")
        swipeUp()
        save("theme-palette-scrolled")
        subTab("Elements").performClick()
        compose.waitForIdle()
        save("theme-elements")
        subTab("Quick panel").performClick()
        compose.waitForIdle()
        save("theme-quick-panel")
        subTab("AI").performClick()
        compose.waitForIdle()
        save("theme-ai")
    }

    /** True when the debug build carries test packs (the CI copies them in; locally, see navigation-v3.md). */
    private fun hasBundledPacks(): Boolean =
        ApplicationProvider.getApplicationContext<Application>().assets.list("packs").orEmpty().isNotEmpty()

    @Test
    fun packs() {
        assumeTrue("needs the bundled debug packs", hasBundledPacks())
        launch("PACKS")
        // The bundled debug packs are seeded on a background thread at start-up.
        compose.waitUntil(60_000) { viewModel.ui.value.packs.isNotEmpty() }
        compose.waitForIdle()
        save("packs")
        scrollTo("Bixbite")
        save("packs-rows")
        compose.onNodeWithText("Bixbite").performClick()
        compose.waitForIdle()
        save("packs-selected")
        scrollTo("More packs")
        save("packs-scrolled")
        scrollTo("Pack per part")
        compose.onNodeWithText("Pack per part").performClick()
        compose.waitForIdle()
        save("packs-per-part")
        scrollTo("Parts")
        save("packs-per-part-parts")
    }

    /** The same pages in the light theme, for contrast checks of the tinted rows and chips. */
    @Test
    @Config(qualifiers = "+notnight")
    fun light() {
        assumeTrue("needs the bundled debug packs", hasBundledPacks())
        launch("PACKS")
        compose.waitUntil(60_000) { viewModel.ui.value.packs.isNotEmpty() }
        compose.waitForIdle()
        save("light-packs")
        scrollTo("Bixbite")
        compose.onNodeWithText("Bixbite").performClick()
        compose.waitForIdle()
        save("light-packs-selected")
        compose.onNodeWithText("Packs").performClick() // no-op on the current tab
        compose.onNodeWithText("Theme").performClick()
        compose.waitForIdle()
        save("light-theme")
        compose.onNodeWithText("Not connected").performClick()
        compose.waitForIdle()
        save("light-status-sheet")
    }

    @Test
    fun library() {
        launch("LIBRARY")
        save("library-icons")
        subTab("Fonts").performClick()
        compose.waitForIdle()
        save("library-fonts")
        subTab("Backgrounds").performClick()
        compose.waitForIdle()
        save("library-backgrounds")
    }

    @Test
    fun settings() {
        launch("SETTINGS")
        save("settings-apps")
        subTab("Tools").performClick()
        compose.waitForIdle()
        save("settings-tools")
        subTab("Hex Next").performClick()
        compose.waitForIdle()
        save("settings-app")
    }

    @Test
    fun statusSheet() {
        launch()
        compose.onNodeWithText("Not connected").performClick()
        compose.waitForIdle()
        save("status-sheet")
    }
}
