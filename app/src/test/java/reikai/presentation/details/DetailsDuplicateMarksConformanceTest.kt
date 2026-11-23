package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.ui.manga.mangaDetailsModel
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.library.ContentType
import reikai.domain.novel.model.NovelChapterFlags
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * The details list marks copies over the chapters before its filters, as the reader skips them, so a copy the
 * unread filter leaves out still marks the one shown. Pinned once over both types.
 */
class DetailsDuplicateMarksConformanceTest {

    interface Probe {
        /** Two copies of chapter 1 from two groups, the first read, under the unread filter: the list's copies. */
        suspend fun copiesWithReadOneFilteredOut(scope: TestScope): Map<Long, List<Long>>?
    }

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter(any()) } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        resetVirtualDispatchers()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a copy the filter leaves out still marks the one shown`(probe: Probe) = runTest {
        probe.copiesWithReadOneFilteredOut(this)?.size shouldBe 2
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        /** A marking with "Show duplicate chapters the reader skips" and the reader's skip both on. */
        fun showingDuplicates(type: ContentType): DuplicateMarking {
            val store = InMemoryPreferenceStore()
            val scope = CoroutineScope(Dispatchers.Unconfined)
            return DuplicateMarking(
                type,
                InMemoryChapterCopyMarks(),
                store.getBoolean("show_skipped_duplicates", true),
                store.getBoolean("skip_dupe", true),
                scope,
                scope,
            )
        }

        suspend fun EntryDetailsBehavior.loadedWhere(
            ready: (EntryDetailsScreenState.Loaded) -> Boolean,
        ): EntryDetailsScreenState.Loaded = withTimeout(30_000) {
            state.filterIsInstance<EntryDetailsScreenState.Loaded>().first(ready)
        }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun copiesWithReadOneFilteredOut(scope: TestScope): Map<Long, List<Long>>? {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            driver.execute(
                null,
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_next_update, state_chapter_fetch_interval, user_notes, " +
                    "remote_memo) VALUES (1, 1, '/manga', 'Title', 0, 1, 0, ${Manga.CHAPTER_SHOW_UNREAD}, 0, 1, 0, " +
                    "0, 0, '', '{}')",
                0,
            ).await()
            listOf(1 to "a", 2 to "b").forEach { (id, group) ->
                driver.execute(
                    null,
                    "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                        "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                        "remote_date_upload, remote_memo) VALUES ($id, 1, '/chapter/$id', 'Chapter 1', '$group', " +
                        "${if (id == 1) 1 else 0}, 0, 0, 1.0, $id, 0, 0, '{}')",
                    0,
                ).await()
            }
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                1L,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(1L),
            ).also { store.put("manga", it) }
            return try {
                val adapter = MangaEntryAdapter(
                    model,
                    mockk(relaxed = true),
                    showingDuplicates(ContentType.MANGA),
                    noVolumePicking(),
                )
                adapter.loadedWhere { it.chapters.duplicates != null && it.hasActiveFilter }.chapters.duplicates?.copies
            } finally {
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun copiesWithReadOneFilteredOut(scope: TestScope): Map<Long, List<Long>>? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novelId = harness.novel(harness.source("alpha"))
                harness.chapter(novelId, 1.0, read = true, url = "/chapter/a", scanlator = "a", name = "Chapter 1")
                harness.chapter(novelId, 1.0, url = "/chapter/b", sourceOrder = 2L, scanlator = "b", name = "Chapter 1")
                harness.novelPreferences.defaultChapterFilterUnread().set(NovelChapterFlags.SHOW_UNREAD)
                val adapter = NovelEntryAdapter(
                    harness.openDetails(novelId),
                    mockk(relaxed = true),
                    showingDuplicates(ContentType.NOVELS),
                    noVolumePicking(),
                )
                adapter.loadedWhere { state ->
                    state.chapters.duplicates != null &&
                        state.chapters.items.filterIsInstance<EntryChapterListItem.Chapter>().size == 1
                }.chapters.duplicates?.copies
            }
    }
}
