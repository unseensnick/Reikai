package reikai.presentation.details

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.merge.gapPresent
import reikai.domain.novel.NovelChapterListEntry
import reikai.domain.novel.model.NovelChapterFlags
import reikai.presentation.novel.details.NovelDetailsState
import reikai.presentation.novel.details.NovelDetailsViewModel
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/**
 * What a details list counts its missing-chapter markers against: every chapter of the series, so
 * neither a filter nor hiding a chapter makes a gap, revealed or not; and "Hide missing chapters" drops
 * only the list's markers, never the header's count. Pinned once over both types.
 */
class DetailsGapConformanceTest {

    /** One details list over chapters 1 to 12, chapter 11 left out of the rows. Each answer is the
     *  markers' counts and the header's. */
    interface Probe {
        /** Chapter 11 read, with the unread filter on. */
        suspend fun elevenFilteredOut(scope: TestScope): Pair<List<Int>, Int>

        /** Chapter 11 hidden, and hidden chapters shown when [revealed]. */
        suspend fun elevenHidden(scope: TestScope, revealed: Boolean): Pair<List<Int>, Int>

        /** Chapter 11 missing from the source, with "Hide missing chapters" on or off as [hideMarkers]. */
        suspend fun elevenMissing(scope: TestScope, hideMarkers: Boolean): Pair<List<Int>, Int>
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The downloaded filter reads a global preference through the app graph; it is not under test.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(DOWNLOADED_FILTER_FILE)
        Dispatchers.resetMain()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chapter a filter hides is not counted missing`(probe: Probe) = runTest {
        probe.elevenFilteredOut(this) shouldBe (emptyList<Int>() to 0)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a hidden chapter is not counted missing`(probe: Probe) = runTest {
        probe.elevenHidden(this, revealed = false) shouldBe (emptyList<Int>() to 0)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `revealing a hidden chapter changes no count`(probe: Probe) = runTest {
        probe.elevenHidden(this, revealed = true) shouldBe (emptyList<Int>() to 0)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a missing chapter is marked in the list`(probe: Probe) = runTest {
        probe.elevenMissing(this, hideMarkers = false) shouldBe (listOf(1) to 1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `hiding missing chapters drops the list's marker and keeps the header's count`(probe: Probe) = runTest {
        probe.elevenMissing(this, hideMarkers = true) shouldBe (emptyList<Int>() to 1)
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val SOURCE = "alpha"

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    /** The manga state as the model assembles it: every row's numbers, the rows it shows. */
    class MangaProbe : Probe {
        override fun toString() = "manga"

        private fun item(number: Int, read: Boolean = false) = ChapterList.Item(
            Chapter.create().copy(
                id = number.toLong(),
                mangaId = 1L,
                name = "Chapter $number",
                chapterNumber = number.toDouble(),
                read = read,
                sourceOrder = 100L - number,
            ),
            Download.State.NOT_DOWNLOADED,
            0,
            isRead = read,
            isBookmarked = false,
        )

        private fun answer(state: MangaViewModel.State.Success) =
            state.chapterListItems.filterIsInstance<ChapterList.MissingCount>().map { it.count } to
                state.missingChapterCount

        private fun state(
            all: List<ChapterList.Item>,
            shown: List<ChapterList.Item> = all,
            flags: Long = 0L,
            revealed: Set<Long> = emptySet(),
            hideMarkers: Boolean = false,
        ) = MangaViewModel.State.Success(
            manga = Manga.create().copy(id = 1L, chapterFlags = flags),
            source = mockk(),
            isFromSource = false,
            chapters = shown,
            availableScanlators = emptySet(),
            excludedScanlators = emptySet(),
            hideMissingChapters = hideMarkers,
            showHidden = revealed.isNotEmpty(),
            hasHiddenChapters = true,
            hiddenChapterIds = revealed,
            gapPresent = all.map { it.chapter }.gapPresent(),
        )

        override suspend fun elevenFilteredOut(scope: TestScope): Pair<List<Int>, Int> =
            answer(state((1..12).map { item(it, read = it == 11) }, flags = Manga.CHAPTER_SHOW_UNREAD))

        override suspend fun elevenHidden(scope: TestScope, revealed: Boolean): Pair<List<Int>, Int> {
            val all = (1..12).map { item(it) }
            return if (revealed) {
                answer(state(all, revealed = setOf(11L)))
            } else {
                answer(state(all, shown = all.filterNot { it.id == 11L }))
            }
        }

        override suspend fun elevenMissing(scope: TestScope, hideMarkers: Boolean): Pair<List<Int>, Int> =
            answer(state((1..12).filterNot { it == 11 }.map { item(it) }, hideMarkers = hideMarkers))
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun elevenFilteredOut(scope: TestScope): Pair<List<Int>, Int> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novelId = harness.novel(harness.source(SOURCE))
                (1..12).forEach { harness.chapter(novelId, it.toDouble(), read = it == 11) }
                harness.novelPreferences.defaultChapterFilterUnread().set(NovelChapterFlags.SHOW_UNREAD)
                answer(loaded(harness.openDetails(novelId)) { it.chapters.size == 11 })
            }

        override suspend fun elevenHidden(scope: TestScope, revealed: Boolean): Pair<List<Int>, Int> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novelId = harness.novel(harness.source(SOURCE))
                val chapters = (1..12).map { harness.chapter(novelId, it.toDouble()) }
                harness.novelPreferences.hiddenChapters().set(setOf(hiddenChapterKey(SOURCE, chapters[10].url)))
                val model = harness.openDetails(novelId)
                val hidden = loaded(model) { it.hasHiddenChapters }
                if (!revealed) return@use answer(hidden)
                model.toggleShowHidden()
                answer(loaded(model) { it.showHidden })
            }

        override suspend fun elevenMissing(scope: TestScope, hideMarkers: Boolean): Pair<List<Int>, Int> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novelId = harness.novel(harness.source(SOURCE))
                (1..12).filterNot { it == 11 }.forEach { harness.chapter(novelId, it.toDouble()) }
                harness.novelPreferences.hideMissingChapters().set(hideMarkers)
                answer(loaded(harness.openDetails(novelId)) { it.chapters.size == 11 })
            }

        private fun answer(state: NovelDetailsState.Loaded) =
            state.chapterListEntries.filterIsInstance<NovelChapterListEntry.Missing>().map { it.count } to
                state.missingChapterCount

        private suspend fun loaded(
            model: NovelDetailsViewModel,
            ready: (NovelDetailsState.Loaded) -> Boolean,
        ): NovelDetailsState.Loaded = withContext(Dispatchers.Default) {
            withTimeout(10_000) { model.state.first { it is NovelDetailsState.Loaded && ready(it) } }
        } as NovelDetailsState.Loaded
    }
}
