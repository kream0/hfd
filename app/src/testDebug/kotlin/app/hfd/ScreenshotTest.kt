package app.hfd

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.hfd.core.progress.Event
import app.hfd.core.progress.Mode
import app.hfd.core.srs.Rating
import app.hfd.ui.AppRoot
import app.hfd.ui.AppViewModel
import app.hfd.ui.Tab
import app.hfd.ui.components.neededAudio
import app.hfd.ui.theme.HfdTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * Renders the main screens with Robolectric's native graphics, in French (the website's language)
 * and in both themes (`<screen>.png` black, `<screen>-paper.png` paper, in the same state), so
 * layouts and the Qur'an text rendering can be checked without a phone. Run by
 * .github/workflows/screenshots.yml, which turns them into the website's WebPs in docs/screenshots.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "fr-w400dp-h880dp-xxhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("hfd.screenshots") ?: "build/screenshots").apply { mkdirs() }

    /** The theme drawn: black, or paper. */
    private var dark by mutableStateOf(true)

    /** [name] in black, then `[name]-paper` in paper: the same state, lined up pixel for pixel. */
    private fun both(name: String) {
        ui { dark = true }
        shot(name)
        ui { dark = false }
        shot("$name-paper")
        ui { dark = true }
    }

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

    /** If the test stalls, writes every thread's stack to hang.txt next to the screenshots. */
    private fun watchdog(afterMs: Long) = Thread {
        try {
            Thread.sleep(afterMs)
        } catch (_: InterruptedException) {
            return@Thread
        }
        val dump = Thread.getAllStackTraces().entries.joinToString("\n\n") { (t, stack) ->
            "${t.name} (${t.state})\n" + stack.joinToString("\n") { "    at $it" }
        }
        File(out, "hang.txt").writeText(dump)
    }.apply {
        isDaemon = true
        start()
    }

    private fun ui(block: () -> Unit) {
        rule.runOnUiThread(block)
        rule.mainClock.advanceTimeBy(100)
        rule.waitForIdle()
    }

    @Test
    fun screens() {
        File(out, "hang.txt").delete()
        val watchdog = watchdog(180_000)
        Graph.player.connectable = false
        // The speech model "downloaded", so Recite shows its screen rather than the download card.
        File(rule.activity.noBackupFilesDir, "models/${app.hfd.recite.SpeechModel.DEFAULT.file}").apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(1))
        }
        val vm = AppViewModel()
        rule.mainClock.autoAdvance = false
        rule.setContent { HfdTheme(dark = dark) { AppRoot(vm) } }
        val deadline = System.currentTimeMillis() + 60_000
        while (!(Graph.content.content.value != null && Graph.progress.loaded.value)) {
            check(System.currentTimeMillis() < deadline) { "Content didn't load" }
            rule.mainClock.advanceTimeBy(100)
            Thread.sleep(50)
            rule.waitForIdle()
        }
        val content = Graph.content.content.value!!

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
        // The review reminder on, so its setting shows.
        ui { Graph.settings.update { it.copy(remindReviews = true) } }
        // The passages shown already on the phone (there is no network here: "waiting for network").
        ui {
            val s = Graph.settings.current
            for (id in listOf("kursi", "tawba-end", "ikhlas")) {
                for (ref in neededAudio(content.fadila(id)!!, s)) {
                    val file = Graph.audio.file(s.reciterInfo, ref).apply { parentFile?.mkdirs(); writeBytes(ByteArray(1)) }
                    Graph.audio.added(s.reciterInfo, ref, file)
                }
            }
        }

        // Each screen in both themes, one after the other, so the pair shows the same state.
        ui { vm.selectTab(Tab.HOME) }
        both("home")
        ui { vm.selectTab(Tab.FADAIL) }
        both("fadail")
        ui { vm.openFadila("kursi") }
        both("kursi")
        // The reading view: āya text, āya-end markers, translation.
        rule.onNodeWithTag("reading").performScrollToIndex(1)
        both("kursi-text")
        // Listening: the word being recited lit (as if 2:255 were playing at its seventh word).
        ui { app.hfd.ui.components.PlayingWord.preview.value = app.hfd.core.quran.AyahRef(2, 255) to 6 }
        both("listen")
        ui { app.hfd.ui.components.PlayingWord.preview.value = null }
        rule.onNodeWithTag("reading").performScrollToIndex(0)
        ui { vm.openFadila("tawba-end") }
        both("tawba-end")
        ui {
            vm.fadila = null
            vm.selectTab(Tab.STATS)
        }
        both("stats")
        ui { vm.selectTab(Tab.SETTINGS) }
        both("settings")
        // performScrollTo() waits for an animated scroll that the hand-driven clock never
        // advances; ScrollBy only starts it, and shot() runs the clock.
        // (positionInRoot: boundsInRoot is clipped to the viewport, empty for an off-screen node.)
        val top = rule.onNodeWithText(rule.activity.getString(R.string.remind_reviews)).fetchSemanticsNode().positionInRoot.y
        rule.onNodeWithTag("settings").performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, top - 300f) }
        both("reminders")
        ui { vm.learn(content.fadila("ikhlas")!!) }
        both("learn")
        ui { vm.review() }
        both("review")
        // Recite mode after three recognised chunks, the last one skipping "wa-lam".
        ui {
            vm.recite(content.fadila("ikhlas")!!)
            Graph.recite.value!!.run {
                onHeard("قل هو الله أحد")
                onHeard("الله الصمد")
                onHeard("لم يلد يولد")
            }
        }
        both("recite")
        ui {
            Graph.closeRecite()
            vm.flow = null
        }
        watchdog.interrupt()
    }
}
