package reikai.presentation.reader

import androidx.lifecycle.SavedStateHandle
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.tts.TtsPauseReason
import reikai.domain.novel.tts.TtsPlayback
import reikai.domain.reader.ChapterProgress
import reikai.domain.reader.PROGRESS_SAVE_DELAY
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Read aloud going on with no reader open, over a real reader model and the app's one session. */
class ReadAloudSessionTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    private var now = Instant.fromEpochMilliseconds(1_000_000_000_000L)

    private fun sessionTest(block: suspend TestScope.(NovelReaderViewModelHarness) -> Unit) = runTest {
        mockkObject(Clock.System)
        try {
            every { Clock.System.now() } answers { now }
            NovelReaderViewModelHarness.create(testScheduler).use {
                it.novelPreferences.readerTtsAutoPageAdvance().set(true)
                block(it)
            }
        } finally {
            unmockkObject(Clock.System)
        }
    }

    private class Reading(
        val model: NovelReaderViewModel,
        val surface: HarnessSurface,
        val chapters: List<SeededChapter>,
        val source: FakeNovelSource,
        val novel: Long,
    )

    /** A reader of a three-chapter novel, reading chapter 1's two paragraphs aloud from the first. */
    private suspend fun TestScope.reading(
        harness: NovelReaderViewModelHarness,
        privateSource: Boolean = false,
        /** Chapter 2's text as the source answers it, set before the reader warms it. */
        secondChapter: String? = null,
        savedState: SavedStateHandle = SavedStateHandle(),
    ): Reading {
        val source = harness.source("src")
        if (privateSource) harness.incognito(source)
        val novel = harness.novel(source)
        val chapters = (1..3).map { harness.chapter(novel, it.toDouble()) }
        secondChapter?.let { source.texts[chapters[1].url] = it }
        val model = harness.open(novel, chapters[0].id, savedState = savedState)
        advanceUntilIdle()
        val surface = HarnessSurface(
            chapters = mutableMapOf(chapters[0].id to listOf("One", "Two")),
            firstVisible = ReadAloudPosition(chapters[0].id, 0),
        )
        model.readAloud.attach(surface)
        model.readAloud.play()
        advanceUntilIdle()
        harness.engines.last().init()
        advanceUntilIdle()
        return Reading(model, surface, chapters, source, novel)
    }

    /** The reader closed: its page let go of first, as the Activity stopping does, then its model. */
    private fun TestScope.closeReader(harness: NovelReaderViewModelHarness, reading: Reading) {
        reading.model.readAloud.detach(reading.surface)
        harness.leave()
        runCurrent()
    }

    private fun TestScope.finishParagraph(harness: NovelReaderViewModelHarness, said: Boolean = true) {
        harness.engines.last().finishLast(said)
        advanceUntilIdle()
    }

    private val NovelReaderViewModelHarness.spoken get() = engines.last().spoken

    @Test
    fun `read aloud goes on into the next chapter with no reader open`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)

        repeat(2) { finishParagraph(harness) }

        harness.spoken.last() shouldBe "From the source: ${reading.chapters[1].url}"
    }

    @Test
    fun `a chapter spoken to its end with no reader open is marked read`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)

        repeat(2) { finishParagraph(harness) }

        harness.isRead(reading.chapters[0]) shouldBe true
    }

    /** History is what a process kill reopens on, so the chapter speech reached is stamped as it arrives. */
    @Test
    fun `the chapter reached with no reader open is stamped in History`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)

        repeat(2) { finishParagraph(harness) }

        harness.historyOf(reading.novel).contains(reading.chapters[1].id) shouldBe true
    }

    @Test
    fun `a chapter read aloud in incognito is not stamped in History`() = sessionTest { harness ->
        val reading = reading(harness, privateSource = true)
        closeReader(harness, reading)

        repeat(2) { finishParagraph(harness) }

        harness.historyOf(reading.novel) shouldBe emptyList()
    }

    @Test
    fun `closing the reader while the next chapter loads keeps read aloud going`() = sessionTest { harness ->
        val reading = reading(harness)
        val gate = CompletableDeferred<Unit>()
        reading.source.holding[reading.chapters[1].url] = gate
        reading.model.readAloud.detach(reading.surface)
        finishParagraph(harness)
        harness.engines.last().finishLast()
        runCurrent()

        harness.leave()
        gate.complete(Unit)
        runCurrent()
        advanceTimeBy(1.seconds)
        runCurrent()

        harness.spoken.last() shouldBe "From the source: ${reading.chapters[1].url}"
    }

    @Test
    fun `the open reader follows read aloud into the chapter it reaches`() = sessionTest { harness ->
        val reading = reading(harness)
        reading.model.readAloud.detach(reading.surface)

        repeat(2) { finishParagraph(harness) }

        reading.model.chapter.value?.chapterId shouldBe reading.chapters[1].id
    }

    @Test
    fun `a reader reopened to follow read aloud opens on the chapter it is speaking`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)
        repeat(2) { finishParagraph(harness) }

        val rejoined = harness.open(reading.novel, reading.chapters[0].id, followSession = true)
        advanceUntilIdle()

        rejoined.chapter.value?.chapterId shouldBe reading.chapters[1].id
    }

    /** Read aloud is ahead of every write, and the spoken place is where the reader lands. */
    @Test
    fun `a reader reopened to follow read aloud lands on the spoken place`() = sessionTest { harness ->
        val reading = reading(harness, secondChapter = "<p>Alpha beta</p><p>Gamma delta</p>")
        closeReader(harness, reading)
        repeat(2) { finishParagraph(harness) }
        finishParagraph(harness)

        val rejoined = harness.open(reading.novel, reading.chapters[0].id, followSession = true)
        advanceUntilIdle()

        rejoined.chapter.value?.progress shouldBe ChapterProgress.Percent.ofWhole(50)
    }

    /** One series, one place: the reader asked for another chapter of the series being read aloud. */
    @Test
    fun `opening another chapter of the series read aloud is reading stops it`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)

        harness.open(reading.novel, reading.chapters[2].id)
        advanceUntilIdle()

        harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `opening the chapter read aloud is reading leaves it playing`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)

        harness.open(reading.novel, reading.chapters[0].id)
        advanceUntilIdle()

        harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Playing
    }

    @Test
    fun `a reader of another novel sees nothing read aloud`() = sessionTest { harness ->
        reading(harness)
        val other = harness.novel(harness.source("other"), title = "Other")
        val otherModel = harness.open(other, harness.chapter(other, 1.0).id)
        advanceUntilIdle()

        otherModel.readAloud.owns() shouldBe false
    }

    @Test
    fun `a reader of another novel cannot pause read aloud`() = sessionTest { harness ->
        reading(harness)
        val other = harness.novel(harness.source("other"), title = "Other")
        val otherModel = harness.open(other, harness.chapter(other, 1.0).id)
        advanceUntilIdle()

        otherModel.readAloud.pause()
        advanceUntilIdle()

        harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Playing
    }

    @Test
    fun `starting read aloud on another novel replaces the one being read`() = sessionTest { harness ->
        val first = reading(harness)
        val other = harness.novel(harness.source("other"), title = "Other")
        val otherChapter = harness.chapter(other, 1.0)
        val otherModel = harness.open(other, otherChapter.id)
        advanceUntilIdle()
        otherModel.readAloud.attach(
            HarnessSurface(mutableMapOf(otherChapter.id to listOf("Elsewhere")), ReadAloudPosition(otherChapter.id, 0)),
        )

        otherModel.readAloud.play()
        advanceUntilIdle()

        (harness.readAloudSession.state.value.series?.novelId to harness.engines.first().shutDown) shouldBe
            (other to true)
    }

    /** Speech is reading too, but its time is counted once, by read aloud, and never again by the reader. */
    @Test
    fun `time listened with the reader open is counted once`() = sessionTest { harness ->
        val reading = reading(harness)
        now += 10.minutes

        reading.model.readAloud.pause()
        advanceUntilIdle()
        reading.model.saveHistory()
        advanceUntilIdle()

        harness.readTimeOf(reading.novel, reading.chapters[0]) shouldBe 10.minutes.inWholeMilliseconds
    }

    @Test
    fun `time listened with no reader open is written when read aloud pauses`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)
        now += 5.minutes

        harness.notification.onPause(TtsPauseReason.User)
        advanceUntilIdle()

        harness.readTimeOf(reading.novel, reading.chapters[0]) shouldBe 5.minutes.inWholeMilliseconds
    }

    @Test
    fun `read aloud credits the chapter the time between two paragraphs`() = sessionTest { harness ->
        val reading = reading(harness)
        advanceTimeBy(5.seconds)

        finishParagraph(harness)

        // From the paragraph's start, which the setup's wait for the place speech saved is part of.
        harness.listened[reading.chapters[0].id] shouldBe (5.seconds + PROGRESS_SAVE_DELAY).inWholeMilliseconds
    }

    @Test
    fun `stopping read aloud credits the paragraph being spoken`() = sessionTest { harness ->
        val reading = reading(harness)
        advanceTimeBy(5.seconds)

        reading.model.readAloud.stop()
        advanceUntilIdle()

        // From the paragraph's start, which the setup's wait for the place speech saved is part of.
        harness.listened[reading.chapters[0].id] shouldBe (5.seconds + PROGRESS_SAVE_DELAY).inWholeMilliseconds
    }

    @Test
    fun `a chapter read aloud in incognito is credited no listening`() = sessionTest { harness ->
        harness.incognito.set(true)
        val reading = reading(harness)
        advanceTimeBy(5.seconds)

        finishParagraph(harness)

        harness.listened[reading.chapters[0].id] shouldBe null
    }

    @Test
    fun `a chapter read aloud with no reader open has its length kept`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)

        repeat(2) { finishParagraph(harness) }

        harness.lengths.containsKey(reading.chapters[1].id) shouldBe true
    }

    @Test
    fun `the notification names the novel and the chapter being read`() = sessionTest { harness ->
        val reading = reading(harness)

        harness.notification.last?.let { it.novelId to it.chapterId } shouldBe (reading.novel to reading.chapters[0].id)
    }

    /** Read aloud moves on behind the host's snapshot, and History is what holds where it got to. */
    @Test
    fun `a novel reopens after a process kill on the chapter read aloud reached`() = sessionTest { harness ->
        val savedState = SavedStateHandle()
        val reading = reading(harness, savedState = savedState)
        val snapshot = SavedStateHandle(savedState.keys().associateWith { savedState.get<Any>(it) })
        reading.model.saveHistory()
        closeReader(harness, reading)
        now += 1.minutes
        repeat(2) { finishParagraph(harness) }

        val restored = harness.open(reading.novel, reading.chapters[0].id, savedState = snapshot)
        advanceUntilIdle()

        restored.chapter.value?.chapterId shouldBe reading.chapters[1].id
    }

    /** In incognito nothing is written, so the place speech holds is the only one there is. */
    @Test
    fun `a reader reopened in incognito to follow read aloud lands on the spoken place`() = sessionTest { harness ->
        val reading = reading(harness, privateSource = true, secondChapter = "<p>Alpha beta</p><p>Gamma delta</p>")
        closeReader(harness, reading)
        repeat(3) { finishParagraph(harness) }

        val rejoined = harness.open(reading.novel, reading.chapters[0].id, followSession = true)
        advanceUntilIdle()

        rejoined.chapter.value?.progress shouldBe ChapterProgress.Percent.ofWhole(50)
    }
}
