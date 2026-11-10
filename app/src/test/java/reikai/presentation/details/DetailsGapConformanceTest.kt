package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.tachiyomi.ui.manga.mangaDetailsModel
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.manga.MangaPreferences
import reikai.domain.novel.NovelChapterListEntry
import reikai.domain.novel.model.NovelChapterFlags
import reikai.presentation.novel.details.NovelDetailsState
import reikai.presentation.novel.details.NovelDetailsViewModel
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * What a details list counts its missing-chapter markers against: every chapter of the series, so
 * neither a filter nor hiding a chapter makes a gap, revealed or not; and "Hide missing chapters" drops
 * the list's markers and the header's count alike. Pinned once over both types.
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
        // Both read through Injekt in production; held at their shipped defaults.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter(any()) } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
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
    fun `hiding missing chapters drops the list's marker and the header's count`(probe: Probe) = runTest {
        probe.elevenMissing(this, hideMarkers = true) shouldBe (emptyList<Int>() to 0)
    }

    // Novels only: paging is the novel transport alone, a manga list is always whole.
    @Test
    fun `a chapter stored on another page of a paged novel is not counted missing`() = runTest {
        val counts = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val novelId = harness.novel(harness.source(SOURCE), totalPages = 3L)
            listOf(9, 10, 12).forEach { harness.chapter(novelId, it.toDouble(), page = "2") }
            harness.chapter(novelId, 11.0, page = "3")
            val model = harness.openDetails(novelId)
            loadedWhere(model) { it.pages.size == 3 && it.sourceName == SOURCE }
            model.selectPage(1)
            val page = loadedWhere(model) { it.pageIndex == 1 && it.chapters.size == 3 }
            page.chapterListEntries.filterIsInstance<NovelChapterListEntry.Missing>().map { it.count } to
                page.missingChapterCount
        }

        counts shouldBe (emptyList<Int>() to 0)
    }

    private suspend fun loadedWhere(
        model: NovelDetailsViewModel,
        ready: (NovelDetailsState.Loaded) -> Boolean,
    ): NovelDetailsState.Loaded = withContext(Dispatchers.Default) {
        withTimeout(10_000) { model.state.first { it is NovelDetailsState.Loaded && ready(it) } }
    } as NovelDetailsState.Loaded

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val SOURCE = "alpha"

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    /** A real MangaViewModel over an in-memory database, assembling the state from stored rows. */
    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun elevenFilteredOut(scope: TestScope): Pair<List<Int>, Int> =
            open((1..12).toList(), read = setOf(11), flags = Manga.CHAPTER_SHOW_UNREAD) { model ->
                // The filter applies as the list renders, so the state still holds every row.
                answer(model.success { it.chapters.size == 12 && it.processedChapters.size == 11 })
            }

        override suspend fun elevenHidden(scope: TestScope, revealed: Boolean): Pair<List<Int>, Int> {
            val prefs = EmittingPreferenceStore()
            MangaPreferences(prefs).hiddenChapters().set(setOf(hiddenChapterKey(SOURCE_ID.toString(), url(11))))
            return open((1..12).toList(), prefs = prefs) { model ->
                val hidden = model.success { it.hasHiddenChapters && it.chapters.size == 11 }
                if (!revealed) return@open answer(hidden)
                model.toggleShowHidden()
                answer(model.success { it.showHidden && it.chapters.size == 12 })
            }
        }

        override suspend fun elevenMissing(scope: TestScope, hideMarkers: Boolean): Pair<List<Int>, Int> {
            val prefs = EmittingPreferenceStore()
            LibraryPreferences(prefs).hideMissingChapters.set(hideMarkers)
            return open((1..12).filterNot { it == 11 }, prefs = prefs) { model ->
                answer(model.success { it.chapters.size == 11 })
            }
        }

        private fun answer(state: MangaViewModel.State.Success) =
            state.chapterListItems.filterIsInstance<ChapterList.MissingCount>().map { it.count } to
                state.missingChapterCount

        /** Manga 1 holding [numbers], [read] of them read, under chapter [flags]; then [block]. */
        private suspend fun <T> open(
            numbers: List<Int>,
            read: Set<Int> = emptySet(),
            flags: Long = 0L,
            prefs: EmittingPreferenceStore = EmittingPreferenceStore(),
            block: suspend (MangaViewModel) -> T,
        ): T {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            driver.execute(
                null,
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_next_update, state_chapter_fetch_interval, user_notes, " +
                    "remote_memo) VALUES ($MANGA_ID, $SOURCE_ID, '/manga', 'Title', 0, 1, 0, $flags, 0, 1, 0, 0, 0, " +
                    "'', '{}')",
                0,
            ).await()
            numbers.forEach { n ->
                driver.execute(
                    null,
                    "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                        "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                        "remote_date_upload, remote_memo) VALUES ($n, $MANGA_ID, '${url(n)}', 'Chapter $n', NULL, " +
                        "${if (n in read) 1 else 0}, 0, 0, $n.0, ${100 - n}, 0, 0, '{}')",
                    0,
                ).await()
            }
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                MANGA_ID,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(MANGA_ID),
                prefs = prefs,
            ).also { store.put("manga", it) }
            return try {
                block(model)
            } finally {
                // The model reads on the real IO dispatcher, so it stops before the database closes.
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }

        private fun url(number: Int) = "/chapter/$number"

        private suspend fun MangaViewModel.success(
            ready: (MangaViewModel.State.Success) -> Boolean,
        ): MangaViewModel.State.Success = withContext(Dispatchers.Default) {
            withTimeout(30_000) { state.filterIsInstance<MangaViewModel.State.Success>().first(ready) }
        }

        private companion object {
            const val MANGA_ID = 1L
            const val SOURCE_ID = 1L
        }
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
