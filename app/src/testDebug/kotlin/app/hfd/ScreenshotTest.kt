package app.hfd

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
        // (No microphone here: Recite's screen is shown as opened, not listening.)
        app.hfd.recite.ReciteSession.listensAtOnce = false
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
        // Listening: the word being recited lit (as if 2:255 were playing at its seventh word),
        // and the bar holding the passage (its button listening, as after "Écouter" was chosen).
        ui {
            Graph.settings.update { it.copy(barListen = true) }
            val kursi = content.fadila("kursi")!!
            val ayat = kursi.ayat
            app.hfd.ui.components.PlayingWord.preview.value = app.hfd.core.quran.AyahRef(2, 255) to 6
            Graph.playingPreview.value = app.hfd.playback.NowPlaying(
                app.hfd.playback.PlaySession("kursi", "Āyat al-Kursī", ayat, 0, ayat.lastIndex),
                app.hfd.core.playback.PlanSpec(ayat, repeatEach = 3),
                Graph.settings.current.reciterInfo,
                app.hfd.core.playback.PlanItem.Ayah(app.hfd.core.playback.Cursor(rep = 2), ayat[0], 3),
                null,
            )
        }
        both("listen")
        // The same bar on a tab (what is listened to, a tap opens it), and under another text.
        ui { vm.fadila = null }
        both("fadail-listening")
        ui { vm.openFadila("kursi") }
        ui {
            app.hfd.ui.components.PlayingWord.preview.value = null
            Graph.playingPreview.value = null
            Graph.settings.update { it.copy(barListen = false) }
        }
        rule.onNodeWithTag("reading").performScrollToIndex(0)
        ui { vm.openFadila("tawba-end") }
        both("tawba-end")
        // The Qur'an section: the sūras, and one open.
        ui {
            vm.fadila = null
            vm.selectTab(Tab.QURAN)
        }
        both("quran")
        // By juzʾ; then a search of the text (words typed plainly, found in the muṣḥaf's spelling).
        rule.onNodeWithText(rule.activity.getString(R.string.quran_view_juz)).performSemanticsAction(SemanticsActions.OnClick)
        both("quran-juz")
        rule.onNodeWithText(rule.activity.getString(R.string.quran_view_suras)).performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("search").performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("الحي القيوم")) }
        both("quran-search")
        rule.onNodeWithTag("search").performSemanticsAction(SemanticsActions.SetText) { it(androidx.compose.ui.text.AnnotatedString("")) }
        // A sūra to read; then opened where a juzʾ begins, an āya kept as a favourite.
        ui { vm.openSura(18) }
        both("sura")
        // The way anywhere else, from the bar over the text: the search and the lists.
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.reader_go)).performSemanticsAction(SemanticsActions.OnClick)
        both("go")
        ui { rule.activity.onBackPressedDispatcher.onBackPressed() }
        // The colours of tajwīd: one āya's rules (from the āya's sheet), and from there their legend, where they are chosen.
        rule.onNodeWithText("18:2", substring = true).performSemanticsAction(SemanticsActions.OnClick)
        rule.mainClock.advanceTimeBy(600)
        rule.onNodeWithText(rule.activity.getString(R.string.tajweed_of_aya)).performSemanticsAction(SemanticsActions.OnClick)
        both("tajweed-aya")
        rule.onNodeWithText(rule.activity.getString(R.string.tajweed_open)).performSemanticsAction(SemanticsActions.OnClick)
        both("tajweed")
        ui { rule.activity.onBackPressedDispatcher.onBackPressed() }
        // The full player, behind the one bar the app listens from; then a passage's, with its repeats.
        rule.onNodeWithTag("player-open").performSemanticsAction(SemanticsActions.OnClick)
        both("player")
        ui { rule.activity.onBackPressedDispatcher.onBackPressed() }
        ui { vm.openFadila("kursi") }
        rule.onNodeWithTag("player-open").performSemanticsAction(SemanticsActions.OnClick)
        both("player-passage")
        ui { rule.activity.onBackPressedDispatcher.onBackPressed() }
        ui { vm.openSura(18) }
        ui {
            if (app.hfd.core.quran.AyahRef(18, 76) !in Graph.favorites) Graph.favorites.toggle(app.hfd.core.quran.AyahRef(18, 76))
            vm.openSura(18, 75)
        }
        both("sura-part")
        // The text never leaves the place it is read at for the āya recited elsewhere (it did: a search
        // result opened while the sūra played was left at once); it follows the recitation it shows.
        fun recited(aya: Int) = ui {
            val ayat = app.hfd.core.playback.QuranListening.ayat(18, 110)
            Graph.playingPreview.value = app.hfd.playback.NowPlaying(
                app.hfd.playback.PlaySession(app.hfd.core.playback.QuranListening.id(18), "Al-Kahf", ayat, 0, ayat.lastIndex),
                app.hfd.core.playback.PlanSpec(ayat, repeatEach = 1),
                Graph.settings.current.reciterInfo,
                app.hfd.core.playback.PlanItem.Ayah(app.hfd.core.playback.Cursor(), ayat[aya - 1], 1),
                null,
            )
        }
        fun at(aya: Int) {
            rule.mainClock.advanceTimeBy(2_000)
            rule.onNodeWithText("Al-Kahf · $aya").assertExists()
        }
        recited(10)
        at(75)
        recited(11)
        at(75)
        ui { vm.openSura(18, 40) }
        at(40)
        recited(12)
        at(40)
        // Recited where it is read, it is followed: on to an āya further down, the text goes there.
        recited(40)
        at(40)
        recited(48)
        at(48)
        ui { Graph.playingPreview.value = null }
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
        // Recite mode after three recognised chunks, the last one skipping "wa-lam". Reciting
        // freely nothing is marked: the recitation waits at the word left out.
        fun reciteIkhlas() = ui {
            vm.recite(content.fadila("ikhlas")!!)
            Graph.recite.value!!.run {
                onHeard("قل هو الله أحد")
                onHeard("الله الصمد")
                onHeard("لم يلد يولد")
            }
        }
        fun marked() = Graph.recite.value!!.ui.value.status.flatten().count { it == app.hfd.core.recite.WordStatus.WRONG || it == app.hfd.core.recite.WordStatus.MISSED }
        reciteIkhlas()
        both("recite")
        Graph.recite.value!!.ui.value.let { r ->
            org.junit.Assert.assertEquals(0, marked())
            org.junit.Assert.assertTrue(r.waiting)
            // (112:3's third word, وَلَمْ.)
            org.junit.Assert.assertEquals(2 to 2, r.next)
        }
        // As the recogniser gives it: each utterance read again and again as it grows, then whole.
        // Followed, it is not "lost" (2.9.0 said so after two utterances: it counted the words
        // the whole reading added to the partial ones, none); two phrases of something else are.
        ui {
            Graph.closeRecite()
            vm.flow = null
        }
        ui {
            vm.recite(content.fadila("ikhlas")!!)
            Graph.recite.value!!.run {
                heardAs("قل", "قل هو الله", "قل هو الله أحد")
                heardAs("الله", "الله الصمد", "الله الصمد")
                heardAs("لم يلد", "لم يلد ولم يولد", "لم يلد ولم يولد")
            }
        }
        Graph.recite.value!!.ui.value.let { r ->
            org.junit.Assert.assertFalse(r.lost)
            org.junit.Assert.assertFalse(r.waiting)
            org.junit.Assert.assertEquals(3 to 0, r.next)
        }
        ui { Graph.recite.value!!.run { heardAs("إنا أعطيناك الكوثر"); heardAs("فصل لربك وانحر") } }
        org.junit.Assert.assertTrue(Graph.recite.value!!.ui.value.lost)
        ui { Graph.recite.value!!.heardAs("ولم يكن", "ولم يكن له كفوا أحد") }
        org.junit.Assert.assertFalse(Graph.recite.value!!.ui.value.lost)
        ui {
            Graph.closeRecite()
            vm.flow = null
        }
        reciteIkhlas()
        // Listening: the button alive with the voice, and the notice in its line (nothing covers the text).
        ui { Graph.recite.value!!.previewListening(level = 0.75f, waiting = true) }
        both("recite-listening")
        rule.onNodeWithTag("recite-notice").assertExists()
        rule.onNodeWithText(rule.activity.getString(R.string.recite_wait)).assertExists()
        ui { Graph.recite.value!!.previewListening(level = 0.2f, muffled = true) }
        both("recite-muffled")
        rule.onNodeWithText(rule.activity.getString(R.string.recite_muffled)).assertExists()
        // Strict: the word left out is marked, and the recitation went on.
        ui {
            Graph.closeRecite()
            vm.flow = null
            Graph.settings.update { it.copy(reciteLevel = app.hfd.recite.ReciteLevel.STRICT) }
        }
        reciteIkhlas()
        both("recite-strict")
        org.junit.Assert.assertEquals(1, marked())
        org.junit.Assert.assertEquals(3 to 0, Graph.recite.value!!.ui.value.next)
        // The level's choice, from the screen's own button.
        rule.onNodeWithTag("recite-level").performSemanticsAction(SemanticsActions.OnClick)
        both("recite-level")
        ui { rule.activity.onBackPressedDispatcher.onBackPressed() }
        // The same with the check of the long vowels on: a madd not held, underlined in its rule's colour.
        ui {
            Graph.closeRecite()
            vm.flow = null
        }
        ui {
            Graph.settings.update { it.copy(reciteLevel = app.hfd.recite.ReciteLevel.TAJWEED) }
            Graph.openRecite("sura:1", listOf(app.hfd.core.quran.AyahRef(1, 6), app.hfd.core.quran.AyahRef(1, 7)))
            vm.flow = app.hfd.ui.PracticeFlow.Recite("sura:1")
            Graph.recite.value!!.run {
                onHeard(content.text(app.hfd.core.quran.AyahRef(1, 6)))
                onHeard(content.text(app.hfd.core.quran.AyahRef(1, 7)))
                previewTajweed(listOf(app.hfd.core.tajweed.TajweedCheck.Finding(0, 11, app.hfd.core.tajweed.Rule.MADD_LAZIM, app.hfd.core.tajweed.TajweedCheck.Fault.TOO_SHORT, 0.5f, 0.3f)), timed = 1, unclear = mapOf(1 to 3))
            }
        }
        both("recite-tajweed")
        ui { Graph.settings.update { it.copy(reciteLevel = app.hfd.recite.ReciteLevel.FREE) } }
        ui {
            Graph.closeRecite()
            vm.flow = null
        }
        // The bar's one button, held: the choice between listening and reciting, floating above it.
        // (Not on Settings or Stats, where nothing is to listen to or recite: the button isn't there.)
        rule.onNodeWithTag("dock-button").assertDoesNotExist()
        ui { vm.selectTab(Tab.QURAN) }
        rule.onNodeWithTag("dock-button").performSemanticsAction(SemanticsActions.OnLongClick)
        both("dock-menu")
        org.junit.Assert.assertTrue(Graph.settings.current.barMenuSeen)
        ui { rule.activity.onBackPressedDispatcher.onBackPressed() }
        ui { vm.reciteAnywhere() }
        both("recite-anywhere")
        // A phrase six sūras say: the places are listed, and nothing is followed yet.
        ui { Graph.recite.value!!.onHeard("ومما رزقناهم ينفقون") }
        org.junit.Assert.assertTrue(Graph.recite.value!!.ui.value.locating)
        org.junit.Assert.assertTrue(Graph.recite.value!!.ui.value.candidates.size > 1)
        both("recite-places")
        // What is said next tells them apart: al-Anfāl, followed from the phrase's first word (8:3's last three).
        ui { Graph.recite.value!!.onHeard("أولئك هم المؤمنون حقا لهم درجات عند ربهم") }
        Graph.recite.value!!.ui.value.let { u ->
            org.junit.Assert.assertEquals("sura:8", u.id)
            org.junit.Assert.assertFalse(u.locating)
            org.junit.Assert.assertEquals(app.hfd.core.quran.AyahRef(8, 4), u.targets[u.next!!.first].ref)
            // (8:3: its last three words said, those before them neither said nor missed.)
            org.junit.Assert.assertEquals(app.hfd.core.recite.WordStatus.PENDING, u.status[2].first())
            org.junit.Assert.assertEquals(app.hfd.core.recite.WordStatus.OK, u.status[2].last())
        }
        both("recite-found")
        // Closed, the sūra found is open at the āya reached: reciting is also how a passage is found.
        ui { vm.closeRecite() }
        rule.mainClock.advanceTimeBy(1_500)
        rule.onNodeWithText("Al-Anfāl · 4").assertExists()
        ui { vm.fadila = null }
        // From a sūra's page (its bar's microphone): the page stays shown where it was read while the place is looked for.
        ui { vm.reciteAnywhere(near = app.hfd.core.quran.AyahRef(18, 10), show = true) }
        Graph.recite.value!!.ui.value.let { u ->
            org.junit.Assert.assertTrue(u.locating)
            org.junit.Assert.assertEquals("sura:18", u.id)
            org.junit.Assert.assertEquals(9, u.view)
        }
        both("recite-page")
        // Recited from another āya of it: followed there. Closed before anything is found, nothing opens.
        ui { Graph.recite.value!!.onHeard(content.text(app.hfd.core.quran.AyahRef(18, 13))) }
        Graph.recite.value!!.ui.value.let { u ->
            org.junit.Assert.assertFalse(u.locating)
            org.junit.Assert.assertEquals(app.hfd.core.quran.AyahRef(18, 14), u.targets[u.next!!.first].ref)
        }
        ui {
            Graph.closeRecite()
            vm.flow = null
        }
        // Begun with the isti'ādha and the basmala, as the recogniser wrote the owner's (13 Nov):
        // they are no place (2.9.0 went to an-Naḥl 98 on them); al-Baqara after them is.
        ui { vm.reciteAnywhere() }
        ui { Graph.recite.value!!.heardAs("أَعَوذُ بِٱللَِّ", "أَعَوذُ بِٱللَِّ مِنَ ٱلشَّيْطَـٰنِ ٱلرَّجِيمِ") }
        org.junit.Assert.assertTrue(Graph.recite.value!!.ui.value.locating)
        ui { Graph.recite.value!!.heardAs("بِسْنللَهٱلرَّحيممِِ ذَٰلِكَ ٱلْكِتَـٰبُ لَا رَيْبَ فِيهِ هُدًى لِّلْمُتَّقِينَ") }
        Graph.recite.value!!.ui.value.let { u ->
            org.junit.Assert.assertEquals("sura:2", u.id)
            org.junit.Assert.assertEquals(app.hfd.core.quran.AyahRef(2, 3), u.targets[u.next!!.first].ref)
        }
        // A place found is a guess until the reciter goes on from it: another passage said
        // instead takes its place by itself.
        ui { Graph.recite.value!!.heardAs(content.text(app.hfd.core.quran.AyahRef(18, 13))) }
        Graph.recite.value!!.ui.value.let { u ->
            org.junit.Assert.assertEquals("sura:18", u.id)
            org.junit.Assert.assertEquals(app.hfd.core.quran.AyahRef(18, 14), u.targets[u.next!!.first].ref)
            org.junit.Assert.assertNull(u.elsewhere)
        }
        // Gone on from: it is theirs. Another passage is then only offered, the recitation stays.
        ui { Graph.recite.value!!.heardAs(content.text(app.hfd.core.quran.AyahRef(18, 14))) }
        ui { Graph.recite.value!!.heardAs(content.text(app.hfd.core.quran.AyahRef(36, 12))) }
        Graph.recite.value!!.ui.value.let { u ->
            org.junit.Assert.assertEquals("sura:18", u.id)
            org.junit.Assert.assertEquals(app.hfd.core.quran.AyahRef(18, 15), u.targets[u.next!!.first].ref)
            org.junit.Assert.assertEquals(app.hfd.core.quran.AyahRef(36, 12), u.elsewhere)
        }
        ui {
            Graph.closeRecite()
            vm.flow = null
        }
        // "Recite from here" on an āya: no looking, the sūra from that āya.
        ui { vm.reciteAnywhere(start = app.hfd.core.quran.AyahRef(36, 2)) }
        Graph.recite.value!!.ui.value.let { u ->
            org.junit.Assert.assertEquals("sura:36", u.id)
            org.junit.Assert.assertEquals(app.hfd.core.quran.AyahRef(36, 2), u.targets[u.next!!.first].ref)
        }
        ui {
            Graph.closeRecite()
            vm.flow = null
        }
        // Which passages show: the list with its switches, al-Kahf switched off.
        ui {
            Graph.hideFadila("kahf", hide = true)
            vm.editingFadail = true
        }
        both("passages")
        ui {
            Graph.hideFadila("kahf", hide = false)
            vm.editingFadail = false
        }
        watchdog.interrupt()
    }
}
