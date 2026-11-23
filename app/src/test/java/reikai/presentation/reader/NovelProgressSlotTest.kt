package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.model.NovelChapter
import reikai.domain.reader.ChapterIncognito
import reikai.domain.reader.ChapterProgress
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import kotlin.time.Instant

/** The one place a novel chapter's position is saved from, by the reader and by read aloud alike. */
class NovelProgressSlotTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    private val stored = mutableMapOf<Long, Long>()
    private val read = mutableSetOf<Long>()

    private val repo = mockk<NovelChapterRepository>(relaxed = true) {
        coEvery { getById(any()) } answers { chapter(firstArg()) }
        coEvery { setLastTextProgress(any(), any()) } answers {
            stored[firstArg()] = secondArg()
            true
        }
    }

    private fun chapter(id: Long) = NovelChapter(
        id = id, novelId = 1L, url = "/$id", name = "Chapter $id", read = id in read, bookmark = false,
        lastTextProgress = stored[id] ?: 0L, chapterNumber = id.toDouble(), sourceOrder = id,
        dateFetch = Instant.fromEpochMilliseconds(0L), dateUpload = null, page = "",
    )

    private fun context(incognito: Boolean = false) = NovelWriteContext(
        memberIds = listOf(1L),
        stitch = emptyList(),
        orderedIds = listOf(1L, 2L),
        incognito = ChapterIncognito(sourceOf = { null }, isIncognito = { incognito }),
    )

    private fun TestScope.slot() = NovelProgressSlot(
        // Not backgroundScope, whose work advanceUntilIdle leaves waiting.
        scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)),
        chapterRepo = repo,
        novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
        chapterFinish = mockk(relaxed = true),
        deleteChaptersBehindReader = mockk(relaxed = true),
        io = StandardTestDispatcher(testScheduler),
    )

    private fun percent(whole: Int) = ChapterProgress.Percent.ofWhole(whole)

    /** With Keep in view off a rotation lands on the screen, not on the paragraph speech had saved. */
    @Test
    fun `while speech holds the place a landing report is dropped`() = runTest {
        val slot = slot()
        slot.reportSpoken(1L, 0.4, context())
        advanceUntilIdle()

        slot.report(1L, percent(10), settled = true, landingSettled = false, context())
        advanceUntilIdle()

        stored[1L] shouldBe 4_000L
    }

    @Test
    fun `the reader moving overwrites the place speech held`() = runTest {
        val slot = slot()
        slot.reportSpoken(1L, 0.4, context())
        advanceUntilIdle()

        slot.report(1L, percent(10), settled = true, landingSettled = true, context())
        advanceUntilIdle()

        stored[1L] shouldBe 1_000L
    }

    @Test
    fun `the latest report of a chapter is the one written`() = runTest {
        val slot = slot()
        slot.reportSpoken(1L, 0.2, context())
        slot.report(1L, percent(30), settled = false, landingSettled = true, context())
        advanceUntilIdle()

        stored[1L] shouldBe 3_000L
    }

    /** Read aloud speaks on in its chapter while the reader moves on, or the reverse. */
    @Test
    fun `a report for another chapter leaves the first one held`() = runTest {
        val slot = slot()
        slot.reportSpoken(1L, 0.2, context())
        slot.report(2L, percent(30), settled = false, landingSettled = true, context())
        runCurrent()

        stored[1L] shouldBe null
    }

    @Test
    fun `each chapter's held write lands`() = runTest {
        val slot = slot()
        slot.reportSpoken(1L, 0.2, context())
        slot.report(2L, percent(30), settled = false, landingSettled = true, context())
        advanceUntilIdle()

        stored shouldBe mapOf(1L to 2_000L, 2L to 3_000L)
    }

    @Test
    fun `finishing by speech drops the held paragraph before the end is written`() = runTest {
        val slot = slot()
        slot.reportSpoken(1L, 0.5, context())

        slot.finishSpoken(1L, context())
        advanceUntilIdle()

        stored[1L] shouldBe ChapterProgress.Percent.END.hundredths
    }

    /** The context travels with the write, so one reported before the reader closed lands by its rules. */
    @Test
    fun `a held write in incognito writes nothing`() = runTest {
        val slot = slot()

        slot.reportSpoken(1L, 0.4, context(incognito = true))
        advanceUntilIdle()

        stored[1L] shouldBe null
    }

    @Test
    fun `the held position is what the slot names as unstored`() = runTest {
        val slot = slot()

        slot.reportSpoken(1L, 0.4, context())
        runCurrent()

        slot.unstored(1L) shouldBe percent(40)
    }

    @Test
    fun `releasing the spoken holds lets a landing write`() = runTest {
        val slot = slot()
        slot.reportSpoken(1L, 0.4, context())
        advanceUntilIdle()

        slot.releaseSpokenHolds()
        slot.report(1L, percent(10), settled = true, landingSettled = false, context())
        advanceUntilIdle()

        stored[1L] shouldBe 1_000L
    }
}
