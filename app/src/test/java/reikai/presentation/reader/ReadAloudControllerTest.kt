package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.novel.tts.TtsVoice
import reikai.presentation.recents.EmittingPreferenceStore

class ReadAloudControllerTest {

    private val preferences = NovelPreferences(EmittingPreferenceStore())
    private val engines = mutableListOf<FakeTtsEngine>()
    private val surface = FakeSurface(
        chapters = mutableMapOf(1L to listOf("a", "b", "c")),
        firstVisible = ReadAloudPosition(1L, 1),
    )
    private val navigation = FakeNavigation()
    private val transport = FakeTransport()

    private val engine get() = engines.last()

    // Not backgroundScope, whose tasks advanceUntilIdle leaves unrun. Its own Job, so runTest does not wait
    // on the preference collectors, which never end.
    private fun TestScope.controller() = ReadAloudController(
        scope = CoroutineScope(StandardTestDispatcher(testScheduler) + Job()),
        preferences = preferences,
        createEngine = { enginePackage, onInit -> FakeTtsEngine(enginePackage, onInit).also { engines += it } },
        navigation = navigation,
        transport = transport,
    ).also { it.attach(surface) }

    /** Playing from the screen's first paragraph, "b", with the engine started. */
    private fun TestScope.playing() = controller().also {
        it.play()
        advanceUntilIdle()
        engine.init(true)
        advanceUntilIdle()
    }

    private fun TestScope.act(block: () -> Unit) {
        block()
        advanceUntilIdle()
    }

    @Test
    fun `play from stopped speaks the first paragraph on screen`() = runTest {
        playing()

        engine.spoken shouldBe listOf("b")
    }

    @Test
    fun `play from stopped highlights the paragraph it speaks`() = runTest {
        playing()

        surface.highlights.last() shouldBe ReadAloudPosition(1L, 1)
    }

    @Test
    fun `playing connects the notification controls`() = runTest {
        playing()

        transport.connects shouldBe 1
    }

    @Test
    fun `playing publishes the paragraph and how many the chapter has`() = runTest {
        playing()

        transport.paragraph shouldBe (1 to 3)
    }

    @Test
    fun `the notification's next steps a paragraph on`() = runTest {
        playing()

        act { transport.onNext() }

        engine.spoken shouldBe listOf("b", "c")
    }

    @Test
    fun `the notification's previous steps a paragraph back`() = runTest {
        playing()

        act { transport.onPrevious() }

        engine.spoken shouldBe listOf("b", "a")
    }

    @Test
    fun `the notification's seek speaks the paragraph sought`() = runTest {
        playing()

        act { transport.onSeek(0) }

        engine.spoken shouldBe listOf("b", "a")
    }

    @Test
    fun `nothing is spoken before the engine has started`() = runTest {
        val controller = controller()

        act { controller.play() }

        engine.spoken shouldBe emptyList()
    }

    @Test
    fun `an engine that fails to start stops playback`() = runTest {
        val controller = controller()
        act { controller.play() }

        act { engine.init(false) }

        controller.state.value.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `an engine that fails to start clears the highlight`() = runTest {
        val controller = controller()
        act { controller.play() }

        act { engine.init(false) }

        surface.highlights.last() shouldBe null
    }

    @Test
    fun `an engine that failed to start is replaced on the next play`() = runTest {
        val controller = controller()
        act { controller.play() }
        act { engine.init(false) }

        act { controller.play() }

        engines.size shouldBe 2
    }

    @Test
    fun `a read from here replaced before the surface answers does not move to the screen's paragraph`() = runTest {
        val controller = playing()
        surface.firstVisible = ReadAloudPosition(1L, 0)
        surface.answerDelayMs = SLOW_ANSWER_MS
        controller.readFromHere()

        act { controller.seekToParagraph(2) }

        surface.highlights.last() shouldBe ReadAloudPosition(1L, 2)
    }

    @Test
    fun `finishing a paragraph speaks the next one`() = runTest {
        playing()

        act { engine.finishLast() }

        engine.spoken shouldBe listOf("b", "c")
    }

    @Test
    fun `finishing a paragraph moves the highlight to the next one`() = runTest {
        playing()

        act { engine.finishLast() }

        surface.highlights.last() shouldBe ReadAloudPosition(1L, 2)
    }

    @Test
    fun `a paragraph finishing after a pause speaks nothing more`() = runTest {
        val controller = playing()
        act { controller.pause() }

        act { engine.finishLast() }

        engine.spoken shouldBe listOf("b")
    }

    @Test
    fun `a paragraph finishing after a seek does not step the seeked one on`() = runTest {
        val controller = playing()
        act { controller.seekToParagraph(0) }

        act { engine.finish(0) }

        engine.spoken shouldBe listOf("b", "a")
    }

    @Test
    fun `speech carries on after the surface detaches`() = runTest {
        val controller = playing()
        controller.detach(surface)

        act { engine.finishLast() }

        engine.spoken shouldBe listOf("b", "c")
    }

    @Test
    fun `pause stops the engine and reads as paused`() = runTest {
        val controller = playing()

        act { controller.pause() }

        (controller.state.value.playback to engine.stops) shouldBe (TtsPlayback.Paused to 1)
    }

    @Test
    fun `pause keeps the highlight`() = runTest {
        val controller = playing()

        act { controller.pause() }

        surface.highlights.last() shouldBe ReadAloudPosition(1L, 1)
    }

    @Test
    fun `play from paused speaks the current paragraph again from its start`() = runTest {
        val controller = playing()
        act { controller.pause() }

        act { controller.play() }

        engine.spoken shouldBe listOf("b", "b")
    }

    @Test
    fun `next paragraph speaks the one after`() = runTest {
        val controller = playing()

        act { controller.nextParagraph() }

        engine.spoken shouldBe listOf("b", "c")
    }

    @Test
    fun `previous paragraph speaks the one before`() = runTest {
        val controller = playing()

        act { controller.previousParagraph() }

        engine.spoken shouldBe listOf("b", "a")
    }

    @Test
    fun `previous at the first paragraph stays on it`() = runTest {
        surface.firstVisible = ReadAloudPosition(1L, 0)
        val controller = playing()

        act { controller.previousParagraph() }

        engine.spoken shouldBe listOf("a", "a")
    }

    @Test
    fun `next at the last paragraph ends the chapter`() = runTest {
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()

        act { controller.nextParagraph() }

        controller.state.value.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `a step from paused resumes playback`() = runTest {
        val controller = playing()
        act { controller.pause() }

        act { controller.nextParagraph() }

        controller.state.value.playback shouldBe TtsPlayback.Playing
    }

    @Test
    fun `a step with nothing playing starts from the screen`() = runTest {
        val controller = controller()
        act { controller.previousParagraph() }

        act { engine.init(true) }

        engine.spoken shouldBe listOf("b")
    }

    @Test
    fun `read from here speaks from the paragraph now on screen`() = runTest {
        val controller = playing()
        surface.firstVisible = ReadAloudPosition(1L, 0)

        act { controller.readFromHere() }

        engine.spoken shouldBe listOf("b", "a")
    }

    @Test
    fun `seek past the end clamps to the last paragraph`() = runTest {
        val controller = playing()

        act { controller.seekToParagraph(99) }

        engine.spoken shouldBe listOf("b", "c")
    }

    @Test
    fun `seek while paused resumes playback`() = runTest {
        val controller = playing()
        act { controller.pause() }

        act { controller.seekToParagraph(0) }

        controller.state.value.playback shouldBe TtsPlayback.Playing
    }

    @Test
    fun `the state counts the paragraphs of the chapter being read`() = runTest {
        val controller = playing()

        controller.state.value.paragraphCount shouldBe 3
    }

    @Test
    fun `the chapter ending with auto advance off stops playback`() = runTest {
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()

        act { engine.finishLast() }

        controller.state.value.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `the chapter ending clears the highlight`() = runTest {
        surface.firstVisible = ReadAloudPosition(1L, 2)
        playing()

        act { engine.finishLast() }

        surface.highlights.last() shouldBe null
    }

    @Test
    fun `the chapter ending with no chapter after stops playback`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()

        act { engine.finishLast() }

        controller.state.value.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `a next chapter the renderer already holds carries on at its first paragraph`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.chapters[2L] = listOf("x", "y")
        surface.firstVisible = ReadAloudPosition(1L, 2)
        playing()

        act { engine.finishLast() }

        engine.spoken shouldBe listOf("c", "x")
    }

    @Test
    fun `a sleep timer set for the chapter end stops there instead of reading on`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.chapters[2L] = listOf("x", "y")
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()
        transport.stopAtChapterEnd = true

        act { engine.finishLast() }

        (controller.state.value.playback to engine.spoken) shouldBe (TtsPlayback.Stopped to listOf("c"))
    }

    @Test
    fun `a sleep timer set for the chapter end does not open the next chapter`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        playing()
        transport.stopAtChapterEnd = true

        act { engine.finishLast() }

        navigation.opened shouldBe emptyList()
    }

    @Test
    fun `a next chapter the renderer lacks is opened for read aloud`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        playing()

        act { engine.finishLast() }

        navigation.opened shouldBe listOf(2L)
    }

    @Test
    fun `a chapter opened for read aloud starts once the renderer lands on it`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()
        act { engine.finishLast() }
        surface.chapters[2L] = listOf("x", "y")

        act { controller.onRendererLanded(2L) }

        engine.spoken shouldBe listOf("c", "x")
    }

    @Test
    fun `play after a pause while the next chapter opens does not repeat the last paragraph`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()
        act { engine.finishLast() }
        act { controller.pause() }

        act { controller.play() }

        engine.spoken shouldBe listOf("c")
    }

    @Test
    fun `play after a pause while the next chapter opens starts it once the renderer lands`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()
        act { engine.finishLast() }
        act { controller.pause() }
        act { controller.play() }
        surface.chapters[2L] = listOf("x", "y")

        act { controller.onRendererLanded(2L) }

        engine.spoken shouldBe listOf("c", "x")
    }

    @Test
    fun `a chapter that fails to load while awaited stops playback`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()
        act { engine.finishLast() }

        act { controller.onChapterLoadFailed() }

        controller.state.value.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `a load failure with no chapter awaited leaves playback going`() = runTest {
        val controller = playing()

        act { controller.onChapterLoadFailed() }

        controller.state.value.playback shouldBe TtsPlayback.Playing
    }

    @Test
    fun `a chapter with no paragraphs is passed over`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        navigation.after[2L] = 3L
        surface.chapters[2L] = emptyList()
        surface.chapters[3L] = listOf("z")
        surface.firstVisible = ReadAloudPosition(1L, 2)
        playing()

        act { engine.finishLast() }

        engine.spoken shouldBe listOf("c", "z")
    }

    @Test
    fun `a renderer laying the chapter out again finds the paragraph by its text`() = runTest {
        val controller = playing()
        surface.chapters[1L] = listOf("new", "a", "b", "c")

        act { controller.onRendererLanded(1L) }

        surface.highlights.last() shouldBe ReadAloudPosition(1L, 2)
    }

    @Test
    fun `a renderer laying the chapter out again takes the match nearest the old paragraph`() = runTest {
        surface.chapters[1L] = listOf("a", "b", "c", "b")
        surface.firstVisible = ReadAloudPosition(1L, 3)
        val controller = playing()
        surface.chapters[1L] = listOf("b", "x", "b", "a", "c", "b", "b")

        act { controller.onRendererLanded(1L) }

        surface.highlights.last() shouldBe ReadAloudPosition(1L, 2)
    }

    @Test
    fun `a paragraph missing from the new layout is clamped into it`() = runTest {
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()
        surface.chapters[1L] = listOf("a")

        act { controller.onRendererLanded(1L) }

        surface.highlights.last() shouldBe ReadAloudPosition(1L, 0)
    }

    @Test
    fun `a renderer slow to lay the chapter out again still finds the paragraph once it answers`() = runTest {
        val controller = playing()
        surface.chapters[1L] = listOf("new", "a", "b", "c")
        surface.answerDelayMs = SLOW_ANSWER_MS

        act { controller.onRendererLanded(1L) }

        surface.highlights.last() shouldBe ReadAloudPosition(1L, 2)
    }

    @Test
    fun `a rebuilt renderer is shown the spoken paragraph before its layout answers`() = runTest {
        val controller = playing()
        surface.answerDelayMs = SLOW_ANSWER_MS
        val before = surface.highlights.size

        controller.onRendererLanded(1L)

        surface.highlights.drop(before) shouldBe listOf(ReadAloudPosition(1L, 1))
    }

    @Test
    fun `a chapter being read that joins the window after the landing is highlighted when it joins`() = runTest {
        val controller = playing()
        surface.chapters.remove(1L)
        surface.chapters[2L] = listOf("x")
        act { controller.onRendererLanded(2L) }
        surface.chapters[1L] = listOf("new", "a", "b", "c")

        act { controller.onWindowChanged() }

        surface.highlights.last() shouldBe ReadAloudPosition(1L, 2)
    }

    /** A highlight follows its paragraph back on screen, so one sent again would undo the reader's scroll.
     *  The host reports both in one window update. */
    @Test
    fun `a landing and its window change place the paragraph once`() = runTest {
        val controller = playing()
        surface.chapters[1L] = listOf("new", "a", "b", "c")
        val before = surface.highlights.size

        act {
            controller.onRendererLanded(1L)
            controller.onWindowChanged()
        }

        surface.highlights.drop(before) shouldBe listOf(ReadAloudPosition(1L, 1), ReadAloudPosition(1L, 2))
    }

    @Test
    fun `a chapter read on from the window leaves no earlier paragraph to place`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()
        surface.chapters.remove(1L)
        act { controller.onRendererLanded(3L) }
        surface.chapters[2L] = listOf("x", "y")
        act { engine.finishLast() }
        val before = surface.highlights.size

        act { controller.onWindowChanged() }

        surface.highlights.size shouldBe before
    }

    @Test
    fun `a chapter taken up paused leaves no earlier paragraph to place`() = runTest {
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()
        surface.chapters.remove(1L)
        act { controller.onRendererLanded(3L) }
        act { engine.finishLast() }
        act { controller.pause() }
        surface.chapters[2L] = listOf("x", "y")
        act { controller.onRendererLanded(2L) }
        val before = surface.highlights.size

        act { controller.onWindowChanged() }

        surface.highlights.size shouldBe before
    }

    @Test
    fun `a renderer laying the chapter out again does not restart speech`() = runTest {
        val controller = playing()
        surface.chapters[1L] = listOf("new", "a", "b", "c")

        act { controller.onRendererLanded(1L) }

        engine.spoken shouldBe listOf("b")
    }

    @Test
    fun `the reader opening a chapter stops read aloud`() = runTest {
        val controller = playing()

        act { controller.onUserNavigated() }

        controller.state.value.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `a rate change reaches the engine while speaking`() = runTest {
        playing()

        act { preferences.readerTtsRate().set(1.5f) }

        engine.lastRate shouldBe 1.5f
    }

    @Test
    fun `a pitch change reaches the engine while speaking`() = runTest {
        playing()

        act { preferences.readerTtsPitch().set(0.5f) }

        engine.lastPitch shouldBe 0.5f
    }

    @Test
    fun `a voice change reaches the engine while speaking`() = runTest {
        playing()

        act { preferences.readerTtsVoice().set("voice") }

        engine.lastVoice shouldBe "voice"
    }

    @Test
    fun `an engine change stops playback`() = runTest {
        val controller = playing()

        act { preferences.readerTtsEngine().set("other") }

        controller.state.value.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `an engine change builds the next play's engine from the new package`() = runTest {
        val controller = playing()
        act { preferences.readerTtsEngine().set("other") }

        act { controller.play() }

        engines.map { it.enginePackage } shouldBe listOf("", "other")
    }

    @Test
    fun `stop clears the highlight`() = runTest {
        val controller = playing()

        act { controller.stop() }

        surface.highlights.last() shouldBe null
    }

    @Test
    fun `stop publishes stopped to the notification`() = runTest {
        val controller = playing()

        act { controller.stop() }

        transport.published shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `shutdown releases the engine`() = runTest {
        val controller = playing()

        act { controller.shutdown() }

        engine.shutDown shouldBe true
    }

    @Test
    fun `shutdown releases the notification`() = runTest {
        val controller = playing()

        act { controller.shutdown() }

        transport.released shouldBe true
    }

    private class FakeTtsEngine(val enginePackage: String, private val onInit: (Boolean) -> Unit) : NovelTtsEngine {
        override var isReady = false
        val spoken = mutableListOf<String>()

        /** Every paragraph's finish, kept after a stop, since the real one can already be on its way. */
        private val finishes = mutableListOf<() -> Unit>()
        var stops = 0
        var shutDown = false
        var lastRate: Float? = null
        var lastPitch: Float? = null
        var lastVoice: String? = null

        fun init(ready: Boolean) {
            isReady = ready
            onInit(ready)
        }

        fun finish(index: Int) = finishes[index]()

        fun finishLast() = finishes.last()()

        override fun availableEngines() = emptyList<TtsEngineInfo>()
        override fun availableVoices() = emptyList<TtsVoice>()

        override fun setVoice(voiceName: String) {
            lastVoice = voiceName
        }

        override fun setRate(rate: Float) {
            lastRate = rate
        }

        override fun setPitch(pitch: Float) {
            lastPitch = pitch
        }

        override fun speak(text: String, onDone: () -> Unit) {
            spoken += text
            finishes += onDone
        }

        override fun stop() {
            stops++
        }

        override fun shutdown() {
            shutDown = true
        }
    }

    private class FakeSurface(
        val chapters: MutableMap<Long, List<String>>,
        var firstVisible: ReadAloudPosition?,
    ) : ReadAloudSurface {
        val highlights = mutableListOf<ReadAloudPosition?>()

        /** How long each question takes to answer, in virtual time, as a page still starting up does. */
        var answerDelayMs = 0L

        override suspend fun paragraphs(chapterId: Long): List<String>? {
            delay(answerDelayMs)
            return chapters[chapterId]
        }

        override suspend fun firstVisibleParagraph(): ReadAloudPosition? {
            delay(answerDelayMs)
            return firstVisible
        }

        override fun highlight(position: ReadAloudPosition?) {
            highlights += position
        }
    }

    private class FakeNavigation : ReadAloudNavigation {
        val after = mutableMapOf<Long, Long>()
        val opened = mutableListOf<Long>()

        override fun chapterAfter(chapterId: Long) = after[chapterId]

        override fun openForReadAloud(chapterId: Long) {
            opened += chapterId
        }

        override fun titleOf(chapterId: Long) = "Chapter $chapterId"
    }

    private class FakeTransport : ReadAloudTransport {
        var connects = 0
        var published: TtsPlayback? = null
        var paragraph: Pair<Int, Int>? = null
        var released = false
        var stopAtChapterEnd = false
        var onNext: () -> Unit = {}
        var onPrevious: () -> Unit = {}
        var onSeek: (Int) -> Unit = {}

        override fun connect(
            onPlay: () -> Unit,
            onPause: () -> Unit,
            onStop: () -> Unit,
            onNext: () -> Unit,
            onPrevious: () -> Unit,
            onSeek: (paragraph: Int) -> Unit,
        ) {
            connects++
            this.onNext = onNext
            this.onPrevious = onPrevious
            this.onSeek = onSeek
        }

        override fun publish(playback: TtsPlayback, title: String, paragraph: Int, paragraphCount: Int) {
            published = playback
            this.paragraph = paragraph to paragraphCount
        }

        override fun takeStopAtChapterEnd() = stopAtChapterEnd.also { stopAtChapterEnd = false }

        override fun release() {
            released = true
        }
    }

    private companion object {
        /** Past the page's own three-second wait for images, which a rebuilt page reports ready after. */
        const val SLOW_ANSWER_MS = 10_000L
    }
}
