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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
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

    /** The second novel of the last merged series [reading] built, and its copy of chapter 1. */
    private var member: Long = -1L
    private var partnerCopy: Long = -1L

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
        /** Chapter 1 as the page shows it, and the paragraph read aloud starts on. */
        paragraphs: List<String> = listOf("One", "Two"),
        startAt: Int = 0,
        /** Read as one series with a second novel of its own source. */
        merged: Boolean = false,
        /** The scope the reader that starts read aloud was opened in. */
        sourceScoped: Boolean = false,
    ): Reading {
        val source = harness.source("src")
        if (privateSource) harness.incognito(source)
        val novel = harness.novel(source)
        // Titled when merged, which is what the stitch pairs two sources' copies of a chapter on.
        val chapters = (1..3).map {
            harness.chapter(novel, it.toDouble(), name = if (merged) "Chapter $it - Part $it" else "Chapter $it.0")
        }
        secondChapter?.let { source.texts[chapters[1].url] = it }
        if (merged) {
            val other = harness.novel(harness.source("other"), title = "Other")
            partnerCopy = harness.chapter(other, 1.0, name = "Chapter 1 - Part 1").id
            harness.merge(novel, other)
            member = other
        }
        val model = harness.open(novel, chapters[0].id, sourceScoped = sourceScoped, savedState = savedState)
        advanceUntilIdle()
        val surface = HarnessSurface(
            chapters = mutableMapOf(chapters[0].id to paragraphs),
            firstVisible = ReadAloudPosition(chapters[0].id, startAt),
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

    /**
     * History is what a process kill reopens on, so the chapter speech reached is stamped as it arrives, after
     * the chapter left, which is written with its time heard as it ends, on the same clock reading here.
     */
    @Test
    fun `History shows the chapter reached with no reader open`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)
        now += 30.seconds

        repeat(2) { finishParagraph(harness) }

        harness.lastInHistory() shouldBe reading.chapters[1].id
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

    /** Each reader holds the link, so a verb from one that does not own the session must leave it alone. */
    enum class OwnerVerb(val run: (ReadAloudLink) -> Unit) {
        PAUSE({ it.pause() }),
        STOP({ it.stop() }),
        OPEN_CHAPTER({ it.onUserNavigated() }),
    }

    @ParameterizedTest
    @EnumSource(OwnerVerb::class)
    fun `a reader of another novel cannot pause or stop read aloud`(verb: OwnerVerb) = sessionTest { harness ->
        reading(harness)
        val other = harness.novel(harness.source("other"), title = "Other")
        val otherModel = harness.open(other, harness.chapter(other, 1.0).id)
        advanceUntilIdle()

        verb.run(otherModel.readAloud)
        advanceUntilIdle()

        harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Playing
    }

    @Test
    fun `the reader opening a chapter itself stops read aloud`() = sessionTest { harness ->
        val reading = reading(harness)

        reading.model.open(reading.chapters[2].id)
        advanceUntilIdle()

        harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Stopped
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

    /** Half an hour paused with nobody back on it, except a pause a call made, which resumes by itself. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("pausesLeftAlone")
    fun `a session left paused with no reader on screen stops after half an hour`(
        reason: TtsPauseReason,
        after: TtsPlayback,
    ) = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)
        harness.notification.onPause(reason)
        runCurrent()

        advanceTimeBy(31.minutes)
        runCurrent()

        harness.readAloudSession.state.value.speech.playback shouldBe after
    }

    @Test
    fun `a paused session with its reader on screen does not stop`() = sessionTest { harness ->
        val reading = reading(harness)
        reading.model.readAloud.pause()
        runCurrent()

        advanceTimeBy(31.minutes)
        runCurrent()

        harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Paused
    }

    @Test
    fun `the next chapter's text is fetched while the last paragraphs of this one are spoken`() =
        sessionTest { harness ->
            val reading = reading(harness)
            closeReader(harness, reading)

            repeat(2) { finishParagraph(harness) }

            reading.source.chaptersAsked.contains(reading.chapters[2].url) shouldBe true
        }

    @Test
    fun `a paused session stops half an hour after its reader leaves the screen`() = sessionTest { harness ->
        val reading = reading(harness)
        reading.model.readAloud.pause()
        runCurrent()
        advanceTimeBy(20.minutes)

        reading.model.saveHistory()
        advanceTimeBy(31.minutes)
        runCurrent()

        harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Stopped
    }

    /** Speech can finish a chapter on its first paragraph, before it has stepped anywhere. */
    @Test
    fun `a merged chapter finished on the first paragraph read aloud trims the downloads behind it`() =
        sessionTest { harness ->
            harness.novelPreferences.removeAfterReadSlots().set(0)
            val reading = reading(harness, paragraphs = List(100) { "Paragraph $it" }, startAt = 99, merged = true)
            harness.download(reading.chapters[0], "<p>Kept</p>")
            closeReader(harness, reading)

            harness.readAloudSession.stop()
            advanceUntilIdle()

            harness.deletedAfterRead.contains(reading.chapters[0].id) shouldBe true
        }

    @Test
    fun `stopping read aloud with no reader open deletes what its finishes queued`() = sessionTest { harness ->
        harness.novelPreferences.removeAfterReadSlots().set(0)
        val reading = reading(harness)
        harness.download(reading.chapters[0], "<p>Kept</p>")
        closeReader(harness, reading)
        repeat(2) { finishParagraph(harness) }

        harness.readAloudSession.stop()
        advanceUntilIdle()

        harness.deletedAfterRead.contains(reading.chapters[0].id) shouldBe true
    }

    /** The reader still open drains them itself when it closes, as it always has. */
    @Test
    fun `stopping read aloud with its reader open leaves the queued deletes to the reader`() =
        sessionTest { harness ->
            harness.novelPreferences.removeAfterReadSlots().set(0)
            val reading = reading(harness)
            harness.download(reading.chapters[0], "<p>Kept</p>")
            reading.model.readAloud.detach(reading.surface)
            repeat(2) { finishParagraph(harness) }

            harness.readAloudSession.stop()
            advanceUntilIdle()

            harness.deletedAfterRead.contains(reading.chapters[0].id) shouldBe false
        }

    @Test
    fun `a pause a call made, then lost for good, stops after half an hour`() = sessionTest { harness ->
        val reading = reading(harness)
        closeReader(harness, reading)
        harness.notification.onPause(TtsPauseReason.TransientFocusLoss)
        runCurrent()

        harness.notification.onPause(TtsPauseReason.FocusLoss)
        advanceTimeBy(31.minutes)
        runCurrent()

        harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Stopped
    }

    @Test
    fun `the notification names the chapter read aloud starts in`() = sessionTest { harness ->
        val reading = reading(harness)

        harness.notification.last?.title shouldBe "Chapter 1.0"
    }

    /** Incognito turned on while read aloud plays keeps what it reads next out of History. */
    @Test
    fun `incognito turned on mid-session keeps the chapter read aloud reaches out of History`() =
        sessionTest { harness ->
            val reading = reading(harness)
            closeReader(harness, reading)
            advanceTimeBy(1.seconds)
            finishParagraph(harness)

            harness.incognito.set(true)
            finishParagraph(harness)

            harness.historyOf(reading.novel).contains(reading.chapters[1].id) shouldBe false
        }

    /** An Updates row or a new-chapter notification opens the novel's own source, which is no follow. */
    @Test
    fun `opening another chapter of the novel read aloud is reading from its source stops it`() =
        sessionTest { harness ->
            val reading = reading(harness)
            closeReader(harness, reading)

            harness.open(reading.novel, reading.chapters[2].id, sourceScoped = true)
            advanceUntilIdle()

            harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Stopped
        }

    @Test
    fun `opening another chapter of a merged series from one source stops it`() = sessionTest { harness ->
        val reading = reading(harness, merged = true)
        closeReader(harness, reading)

        harness.open(reading.novel, reading.chapters[2].id, sourceScoped = true)
        advanceUntilIdle()

        harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Stopped
    }

    /** A novel in no merge group reads the same chapters in either scope, so it is the same series. */
    @Test
    fun `a novel in no group opened from its source on the spoken chapter is read aloud's reader`() =
        sessionTest { harness ->
            val reading = reading(harness)
            closeReader(harness, reading)

            val joined = harness.open(reading.novel, reading.chapters[0].id, sourceScoped = true)
            advanceUntilIdle()

            joined.readAloud.owns() shouldBe true
        }

    /** Read aloud started with no order to hand, on chapter 1 of a three-chapter novel, by two paragraphs. */
    private suspend fun TestScope.startedWithoutOrder(harness: NovelReaderViewModelHarness): List<SeededChapter> {
        val source = harness.source("src")
        val novel = harness.novel(source)
        val chapters = (1..3).map { harness.chapter(novel, it.toDouble()) }
        val surface = HarnessSurface(
            chapters = mutableMapOf(chapters[0].id to listOf("One", "Two")),
            firstVisible = ReadAloudPosition(chapters[0].id, 0),
        )
        harness.readAloudSession.start(ReadAloudSeries(novel, listOf(novel), false), null, emptyMap(), surface, null)
        harness.readAloudSession.play()
        advanceUntilIdle()
        harness.engines.last().init()
        advanceUntilIdle()
        return chapters
    }

    @Test
    fun `a write that could not read the order reads it again for the next one`() = sessionTest { harness ->
        harness.failOrderReads = true
        val chapters = startedWithoutOrder(harness)
        harness.failOrderReads = false

        finishParagraph(harness)

        harness.progressOf(chapters[0]) shouldBe 5_000L
    }

    /** The sleep timer set for the chapter's end stops speech in the same step that finishes the chapter. */
    @Test
    fun `a finish waiting on the order is written though read aloud stops meanwhile`() = sessionTest { harness ->
        harness.failOrderReads = true
        val chapters = startedWithoutOrder(harness)
        finishParagraph(harness)
        harness.failOrderReads = false
        harness.notification.stopAtChapterEnd = true

        finishParagraph(harness)

        harness.isRead(chapters[0]) shouldBe true
    }

    /** One source of a merged series is a different view of it, so even the spoken chapter stops speech. */
    @Test
    fun `opening the spoken chapter of a merged series from one source stops read aloud`() =
        sessionTest { harness ->
            val reading = reading(harness, merged = true)
            closeReader(harness, reading)

            harness.open(reading.novel, reading.chapters[0].id, sourceScoped = true)
            advanceUntilIdle()

            harness.readAloudSession.state.value.speech.playback shouldBe TtsPlayback.Stopped
        }

    /** What a reader opened while read aloud plays ends up doing. */
    enum class Outcome { Joins, Stops, Untouched }

    /** Which novel and chapter a launch names: the one read aloud speaks, another of it, its merge partner's, another novel. */
    enum class Target { Spoken, OtherChapter, Member, PartnerCopy, OtherNovel }

    /**
     * Every way a reader can open while read aloud plays, each reachable: a follow is the notification (the
     * session's own view), Continue, History or Stats (group) and the details Resume on a source chip
     * (source); an explicit open is a chapter tap on the All chip (group) or a source chip, an Updates row,
     * a new-chapter notification (source). Member rows need a merged series; the rest are run both ways.
     */
    @ParameterizedTest(name = "{0} {1}: session source {2}, opened source {3}, merged {4}")
    @MethodSource("openings")
    fun `a reader opened while read aloud plays joins it, stops it or leaves it`(
        follow: Boolean,
        target: Target,
        sessionSourceScoped: Boolean,
        openedSourceScoped: Boolean,
        merged: Boolean,
        expected: Outcome,
    ) = sessionTest { harness ->
        val reading = reading(harness, merged = merged, sourceScoped = sessionSourceScoped)
        closeReader(harness, reading)
        val (novel, chapter) = when (target) {
            Target.Spoken -> reading.novel to reading.chapters[0].id
            Target.OtherChapter -> reading.novel to reading.chapters[2].id
            Target.Member -> member to harness.chapter(member, 2.0).id
            Target.PartnerCopy -> member to partnerCopy
            Target.OtherNovel -> harness.novel(harness.source("elsewhere"), title = "Elsewhere").let { other ->
                other to harness.chapter(other, 1.0).id
            }
        }

        val opened = harness.open(novel, chapter, sourceScoped = openedSourceScoped, followSession = follow)
        advanceUntilIdle()

        val session = harness.readAloudSession.state.value
        val outcome = when {
            session.speech.playback == TtsPlayback.Stopped -> Outcome.Stops
            opened.readAloud.owns() && opened.chapter.value?.chapterId == reading.chapters[0].id -> Outcome.Joins
            else -> Outcome.Untouched
        }
        outcome shouldBe expected
    }

    /** A place that waited for the order lands after read aloud stopped, when it is no longer speech's to hold. */
    @Test
    fun `a place written after read aloud stopped does not hold the reader's landing back`() = sessionTest { harness ->
        val gate = CompletableDeferred<Unit>()
        harness.orderGate = gate
        val chapters = startedWithoutOrder(harness)

        harness.readAloudSession.stop()
        gate.complete(Unit)
        advanceUntilIdle()

        harness.progressSlot.isSpokenHeld(chapters[0].id) shouldBe false
    }

    /** The order arrives for one write while an older one still waits on it, which must land first. */
    @Test
    fun `a newer place is not overwritten by an older one that waited for the order`() = sessionTest { harness ->
        val gate = CompletableDeferred<Unit>()
        harness.orderGate = gate
        val chapters = startedWithoutOrder(harness)

        finishParagraph(harness)
        gate.complete(Unit)
        advanceUntilIdle()

        harness.progressOf(chapters[0]) shouldBe 5_000L
    }

    /** Read from here on another chapter moves speech there, which is where its notification reopens. */
    @Test
    fun `a reader reopened from the notification stays on the chapter read aloud was started from`() =
        sessionTest { harness ->
            val reading = reading(harness)
            reading.model.readAloud.detach(reading.surface)
            repeat(2) { finishParagraph(harness) }
            val there = HarnessSurface(
                mutableMapOf(reading.chapters[2].id to listOf("Elsewhere")),
                ReadAloudPosition(reading.chapters[2].id, 0),
            )
            reading.model.readAloud.attach(there)
            reading.model.readAloud.readFromHere()
            advanceUntilIdle()
            reading.model.readAloud.detach(there)
            harness.leave()
            runCurrent()

            val rejoined = harness.open(reading.novel, reading.chapters[2].id, followSession = true)
            advanceUntilIdle()

            rejoined.chapter.value?.chapterId shouldBe reading.chapters[2].id
        }

    /** After a kill there is no read aloud to ask, and the chapter saved belongs to the view it was read in. */
    @Test
    fun `a reader that took read aloud's view reopens in it after a process kill`() = sessionTest { harness ->
        val reading = reading(harness, merged = true, sourceScoped = true)
        closeReader(harness, reading)
        val chapter = harness.chapter(member, 2.0).id
        val savedState = SavedStateHandle()
        harness.open(member, chapter, followSession = true, savedState = savedState)
        advanceUntilIdle()
        harness.readAloudSession.stop()
        harness.leave()
        advanceUntilIdle()

        val restored = harness.open(member, chapter, followSession = true, savedState = savedState)

        (restored.novelId to restored.sourceScoped) shouldBe (reading.novel to true)
    }

    /** Before speech has published a paragraph, the chapter it started on is still where it is. */
    @Test
    fun `a follow before speech has spoken opens on the chapter read aloud started on`() = sessionTest { harness ->
        val novel = harness.novel(harness.source("src"))
        val chapters = (1..3).map { harness.chapter(novel, it.toDouble()) }
        harness.readAloudSession.start(
            ReadAloudSeries(novel, listOf(novel), false),
            null,
            emptyMap(),
            null,
            startChapter = chapters[0].id,
        )

        val opened = harness.open(novel, chapters[2].id, followSession = true)
        advanceUntilIdle()

        opened.chapter.value?.chapterId shouldBe chapters[0].id
    }

    companion object {
        /** A follow takes the session's view; an explicit open joins only in that view on the spoken chapter. */
        @JvmStatic
        fun openings(): List<Arguments> {
            val rows = mutableListOf<Arguments>()
            for (follow in listOf(true, false)) {
                for (session in listOf(false, true)) {
                    for (opened in listOf(false, true)) {
                        for (merged in listOf(false, true)) {
                            val sameView = !merged || session == opened
                            for (target in listOf(Target.Spoken, Target.OtherChapter)) {
                                val joins = follow || (sameView && target == Target.Spoken)
                                rows +=
                                    Arguments.of(
                                        follow,
                                        target,
                                        session,
                                        opened,
                                        merged,
                                        if (joins) Outcome.Joins else Outcome.Stops,
                                    )
                            }
                            if (merged) {
                                rows +=
                                    Arguments.of(
                                        follow,
                                        Target.Member,
                                        session,
                                        opened,
                                        true,
                                        if (follow) Outcome.Joins else Outcome.Stops,
                                    )
                            }
                            // The partner's copy of the spoken chapter is that chapter, so opened in the group
                            // it joins; from its own source it is another novel's view.
                            if (merged) {
                                rows +=
                                    Arguments.of(
                                        follow,
                                        Target.PartnerCopy,
                                        session,
                                        opened,
                                        true,
                                        if (follow || (!session && !opened)) Outcome.Joins else Outcome.Stops,
                                    )
                            }
                        }
                        rows += Arguments.of(follow, Target.OtherNovel, session, opened, false, Outcome.Untouched)
                    }
                }
            }
            return rows
        }

        @JvmStatic
        fun pausesLeftAlone() = listOf(
            Arguments.of(TtsPauseReason.User, TtsPlayback.Stopped),
            Arguments.of(TtsPauseReason.SleepTimer, TtsPlayback.Stopped),
            Arguments.of(TtsPauseReason.FocusLoss, TtsPlayback.Stopped),
            Arguments.of(TtsPauseReason.Refused, TtsPlayback.Stopped),
            Arguments.of(TtsPauseReason.TransientFocusLoss, TtsPlayback.Paused),
        )
    }
}
