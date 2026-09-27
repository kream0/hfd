package app.hfd

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hfd.core.progress.Event
import app.hfd.core.progress.Mode
import app.hfd.core.srs.Rating
import app.hfd.ui.AppRoot
import app.hfd.ui.AppViewModel
import app.hfd.ui.Tab
import app.hfd.ui.theme.HfdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the main screens with Robolectric's native graphics and saves them as PNGs in
 * docs/screenshots (light and dark), so layouts and the Qur'an text rendering can be checked
 * without a phone. Run by .github/workflows/screenshots.yml.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("hfd.screenshots") ?: "build/screenshots").apply { mkdirs() }

    /**
     * Lets the UI settle (the test clock is advanced by hand: infinite dot animations would
     * otherwise keep Compose busy forever), then draws the window into a bitmap.
     */
    private fun shot(name: String) {
        repeat(4) {
            rule.mainClock.advanceTimeBy(250)
            Thread.sleep(100)
            rule.waitForIdle()
        }
        rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            FileOutputStream(File(out, "$name.png")).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    private fun ui(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    @Test
    fun screens() {
        val vm = AppViewModel()
        var dark by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent { HfdTheme(dark = dark) { AppRoot(vm) } }
        val deadline = System.currentTimeMillis() + 60_000
        while (!(Graph.content.content.value != null && Graph.progress.loaded.value)) {
            check(System.currentTimeMillis() < deadline) { "Content didn't load" }
            rule.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
            rule.waitForIdle()
        }

        // A little history so progress rings, strength marks and stats have something to show.
        ui {
            val now = System.currentTimeMillis()
            val day = 86_400_000L
            for (d in 12 downTo 1) Graph.progress.record(Event.Listen(now - d * day, "2:255", (5 + d) * 60_000L, 3))
            for (k in listOf("2:255", "112:1", "112:2")) {
                Graph.progress.record(Event.Rate(now - 3 * day, k, Rating.GOOD, Mode.LEARN, 20_000))
                Graph.progress.record(Event.Rate(now - 3 * day + 700_000, k, Rating.GOOD, Mode.LEARN, 15_000))
            }
            Graph.progress.record(Event.Rate(now - day, "112:3", Rating.HARD, Mode.LEARN, 30_000))
        }

        for (theme in listOf("dark", "light")) {
            ui {
                dark = theme == "dark"
                vm.flow = null
                vm.fadila = null
                vm.selectTab(Tab.HOME)
            }
            shot("$theme-01-home")
            ui { vm.selectTab(Tab.FADAIL) }
            shot("$theme-02-fadail")
            ui { vm.openFadila("kursi-greatest") }
            shot("$theme-03-kursi")
            ui { vm.openFadila("muawwidhatayn") }
            shot("$theme-04-falaq-nas")
            ui {
                vm.fadila = null
                vm.selectTab(Tab.STATS)
            }
            shot("$theme-05-stats")
            ui { vm.selectTab(Tab.SETTINGS) }
            shot("$theme-06-settings")
            ui { vm.learn(Graph.content.content.value!!.fadila("ikhlas-third")!!) }
            shot("$theme-07-learn")
            ui { vm.review() }
            shot("$theme-08-review")
        }
    }
}
