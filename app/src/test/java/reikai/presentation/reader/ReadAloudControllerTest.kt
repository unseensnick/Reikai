package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.tts.NovelTtsEngine
import reikai.domain.novel.tts.TtsEngineInfo
import reikai.domain.novel.tts.TtsPiece
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.novel.tts.TtsUtteranceSplitter
import reikai.domain.novel.tts.TtsVoice
import reikai.presentation.recents.EmittingPreferenceStore
import java.util.Locale

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
    private fun TestScope.controller(
        dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
        startsOnBuild: List<Boolean> = emptyList(),
    ) = ReadAloudController(
        scope = CoroutineScope(dispatcher + Job()),
        preferences = preferences,
        createEngine = { enginePackage, onInit ->
            FakeTtsEngine(enginePackage, onInit, startsOnBuild.getOrNull(engines.size)).also { engines += it }
        },
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
    fun `paragraphs are spoken whole while sentence highlighting is off`() = runTest {
        playing()

        engine.bySentence.last() shouldBe false
    }

    @Test
    fun `a piece starting does not narrow the mark while sentence highlighting is off`() = runTest {
        playing()

        act { engine.pieceStarts.last()(0) }

        surface.ranges.last() shouldBe null
    }

    @Test
    fun `sentence highlighting speaks a paragraph one sentence at a time`() = runTest {
        preferences.readerTtsHighlightSentence().set(true)
        playing()

        engine.bySentence.last() shouldBe true
    }

    @Test
    fun `sentence highlighting needs the paragraph highlight on`() = runTest {
        preferences.readerTtsHighlightSentence().set(true)
        preferences.readerTtsHighlight().set(false)
        playing()

        engine.bySentence.last() shouldBe false
    }

    /** The paragraph is marked a sentence at a time, so marking all of it before the first would flash. */
    @Test
    fun `a paragraph spoken by sentence is not marked whole before its first sentence starts`() = runTest {
        preferences.readerTtsHighlightSentence().set(true)
        playing()

        surface.highlights shouldBe emptyList()
    }

    @Test
    fun `a sentence starting marks its range in the paragraph`() = runTest {
        preferences.readerTtsHighlightSentence().set(true)
        playing()

        act { engine.pieceStarts.last()(0) }

        surface.ranges.last() shouldBe (0 until 1)
    }

    @Test
    fun `a sentence starting in a paragraph since replaced marks nothing`() = runTest {
        preferences.readerTtsHighlightSentence().set(true)
        val controller = playing()
        val stale = engine.pieceStarts.last()
        act { controller.nextParagraph() }

        act { stale(0) }

        surface.ranges shouldBe emptyList()
    }

    /** Through a rebuild, which marks what the controller holds rather than what the speak just drew. */
    @Test
    fun `a sentence is not carried into the next paragraph`() = runTest {
        preferences.readerTtsHighlightSentence().set(true)
        val controller = playing()
        act { engine.pieceStarts.last()(0) }
        act { engine.finishLast() }

        act { controller.onRendererLanded(1L) }

        surface.ranges.last() shouldBe null
    }

    @Test
    fun `a rebuild that finds the paragraph again keeps its sentence marked`() = runTest {
        preferences.readerTtsHighlightSentence().set(true)
        val controller = playing()
        act { engine.pieceStarts.last()(0) }

        act { controller.onRendererLanded(1L) }

        surface.ranges.last() shouldBe (0 until 1)
    }

    /**
     * The paragraph read first has three sentences, so a sentence step and a paragraph step land on
     * different text inside it, and its neighbours have sentences to reach past either end.
     */
    private fun sentenceChapter() {
        preferences.readerTtsHighlightSentence().set(true)
        surface.chapters[1L] = listOf("One. Two.", "Three. Four. Five.", "Six. Seven.")
    }

    @Test
    fun `next moves one sentence while sentences are spoken`() = runTest {
        sentenceChapter()
        val controller = playing()

        act { controller.nextParagraph() }

        engine.spoken.last() shouldBe "Four. Five."
    }

    @Test
    fun `next from a paragraph's last sentence starts the next paragraph`() = runTest {
        sentenceChapter()
        val controller = playing()
        act { controller.nextParagraph() }
        act { controller.nextParagraph() }

        act { controller.nextParagraph() }

        engine.spoken.last() shouldBe "Six. Seven."
    }

    @Test
    fun `previous from a paragraph's first sentence goes back to the last sentence before it`() = runTest {
        sentenceChapter()
        val controller = playing()

        act { controller.previousParagraph() }

        engine.spoken.last() shouldBe "Two."
    }

    @Test
    fun `previous inside a paragraph goes back one sentence`() = runTest {
        sentenceChapter()
        val controller = playing()
        act { controller.nextParagraph() }
        act { controller.nextParagraph() }

        act { controller.previousParagraph() }

        engine.spoken.last() shouldBe "Four. Five."
    }

    @Test
    fun `a paused paragraph laid out again as other text resumes from its start`() = runTest {
        sentenceChapter()
        val controller = playing()
        act { engine.pieceStarts.last()(2) }
        act { controller.pause() }
        surface.chapters[1L] = listOf("One. Two.", "Three, split. Four. Five.", "Six. Seven.")
        act { controller.onRendererLanded(1L) }

        act { controller.play() }

        engine.spoken.last() shouldBe "Three, split. Four. Five."
    }

    @Test
    fun `resuming a paragraph spoken by sentence carries on from the sentence paused in`() = runTest {
        sentenceChapter()
        val controller = playing()
        act { engine.pieceStarts.last()(1) }
        act { controller.pause() }

        act { controller.play() }

        engine.spoken.last() shouldBe "Four. Five."
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
    fun `the notification's pause pauses reading`() = runTest {
        val controller = playing()

        act { transport.onPause() }

        controller.state.value.playback shouldBe TtsPlayback.Paused
    }

    @Test
    fun `the notification's play resumes a pause`() = runTest {
        val controller = playing()
        act { transport.onPause() }
        val paused = controller.state.value.playback

        act { transport.onPlay() }

        (paused to controller.state.value.playback) shouldBe (TtsPlayback.Paused to TtsPlayback.Playing)
    }

    @Test
    fun `the notification's stop stops reading`() = runTest {
        val controller = playing()

        act { transport.onStop() }

        controller.state.value.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `the notification's previous steps a paragraph back`() = runTest {
        playing()

        act { transport.onPrevious() }

        engine.spoken shouldBe listOf("b", "a")
    }

    /** Two paragraphs on, which neither the notification's next nor its previous reaches. */
    @Test
    fun `the notification's seek speaks the paragraph sought`() = runTest {
        surface.firstVisible = ReadAloudPosition(1L, 0)
        playing()

        act { transport.onSeek(2) }

        engine.spoken shouldBe listOf("a", "c")
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
    fun `an engine replaced before it starts ignores the old engine's start`() = runTest {
        val controller = controller()
        act { controller.play() }
        act { preferences.readerTtsEngine().set("other") }
        act { controller.play() }

        act { engines[0].init(true) }

        engines[1].spoken shouldBe emptyList()
    }

    /**
     * Played on engines reporting [starts] from their constructors. Main.immediate runs the init callback's
     * launch inline once the building coroutine was resumed from a real wait, so the start lands before
     * the build returns; the unconfined scope and a surface slow to answer reproduce that here.
     */
    private fun TestScope.playedOnEnginesStarting(vararg starts: Boolean): ReadAloudController {
        surface.answerDelayMs = 1
        return controller(UnconfinedTestDispatcher(testScheduler), starts.toList()).also { act { it.play() } }
    }

    @Test
    fun `an engine that fails while being built speaks nothing`() = runTest {
        playedOnEnginesStarting(false)

        engines[0].spoken shouldBe emptyList()
    }

    @Test
    fun `an engine that fails while being built stops playback`() = runTest {
        val controller = playedOnEnginesStarting(false)

        controller.state.value.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `an engine that fails while being built is shut down`() = runTest {
        playedOnEnginesStarting(false)

        engines[0].shutDown shouldBe true
    }

    /**
     * A wait for the failed engine's start, left behind, is taken up by the next engine's start while it
     * is still being built, which builds a third engine and leaves reading on one that never starts.
     */
    @Test
    fun `a play after an engine failed while being built reads on with the engine it built`() = runTest {
        val controller = playedOnEnginesStarting(false, true)
        act { controller.play() }

        act { engines[1].finishLast() }

        engines[1].spoken shouldBe listOf("b", "c")
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
    fun `resuming after a paused handoff starts the new chapter at its first sentence`() = runTest {
        sentenceChapter()
        preferences.readerTtsAutoPageAdvance().set(true)
        navigation.after[1L] = 2L
        surface.firstVisible = ReadAloudPosition(1L, 2)
        val controller = playing()
        act { engine.pieceStarts.last()(1) }
        act { engine.finishLast() }
        act { controller.pause() }
        surface.chapters[2L] = listOf("Eight. Nine.")
        act { controller.onRendererLanded(2L) }

        act { controller.play() }

        engine.spoken.last() shouldBe "Eight. Nine."
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
    fun `a saved rate reaches the engine once it starts`() = runTest {
        preferences.readerTtsRate().set(1.5f)

        playing()

        engine.lastRate shouldBe 1.5f
    }

    @Test
    fun `a saved pitch reaches the engine once it starts`() = runTest {
        preferences.readerTtsPitch().set(0.5f)

        playing()

        engine.lastPitch shouldBe 0.5f
    }

    @Test
    fun `a saved voice reaches the engine once it starts`() = runTest {
        preferences.readerTtsVoice().set("voice")

        playing()

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

    /**
     * [startsOnBuild] reports its start from the constructor, as TextToSpeech reports a failure when no
     * engine is installed; null leaves the start to [init].
     */
    private class FakeTtsEngine(
        val enginePackage: String,
        private val onInit: (Boolean) -> Unit,
        startsOnBuild: Boolean?,
    ) : NovelTtsEngine {
        val spoken = mutableListOf<String>()
        val bySentence = mutableListOf<Boolean>()

        /** Every paragraph's piece-start callback, in the order they were spoken. */
        val pieceStarts = mutableListOf<(Int) -> Unit>()

        /** Every paragraph's finish, kept after a stop, since the real one can already be on its way. */
        private val finishes = mutableListOf<() -> Unit>()
        var stops = 0
        var shutDown = false
        var lastRate: Float? = null
        var lastPitch: Float? = null
        var lastVoice: String? = null

        init {
            startsOnBuild?.let(onInit)
        }

        fun init(ready: Boolean) = onInit(ready)

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

        override fun pieces(text: String, bySentence: Boolean): List<TtsPiece> {
            this.bySentence += bySentence
            return TtsUtteranceSplitter.pieces(text, maxLength = 1000, Locale.ENGLISH, bySentence)
        }

        /** What was spoken, as its pieces joined, so a paragraph spoken whole reads as itself. */
        override fun speak(pieces: List<TtsPiece>, onPieceStart: (index: Int) -> Unit, onDone: () -> Unit) {
            spoken += pieces.joinToString(" ") { it.text }
            pieceStarts += onPieceStart
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
        val ranges = mutableListOf<IntRange?>()

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

        override fun highlight(position: ReadAloudPosition?, range: IntRange?) {
            highlights += position
            ranges += range
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
        var onPlay: () -> Unit = {}
        var onPause: () -> Unit = {}
        var onStop: () -> Unit = {}
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
            this.onPlay = onPlay
            this.onPause = onPause
            this.onStop = onStop
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
