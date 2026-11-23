package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.manga.mangaDetailsModel
import eu.kanade.tachiyomi.ui.manga.seedManga
import exh.debug.DebugToggles
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
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
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga

/**
 * A volume's select-all control is a bulk verb, so it leaves no row to range from: a long press after it picks
 * only the pressed row. Pinned once over both types.
 */
class DetailsBlockSelectionConformanceTest {

    interface Probe {
        /** Three chapters; the first selected through its block, then the third long-pressed. The ids, in order. */
        suspend fun blockThenLongPress(scope: TestScope): Pair<List<Long>, Set<Long>>
    }

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter(any()) } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
        mockkStatic(SOURCE_EXTENSIONS_FILE)
        every { any<Source>().getNameForMangaInfo(any()) } returns "source"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        resetVirtualDispatchers()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a long press after a block selection picks only the pressed row`(probe: Probe) = runTest {
        val (ids, selected) = probe.blockThenLongPress(this)

        selected shouldBe setOf(ids.first(), ids.last())
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val SOURCE_EXTENSIONS_FILE = "eu.kanade.tachiyomi.source.SourceExtensionsKt"

        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        /** Block-selects the first of the three rows shown, long-presses the last, and reads the selection. */
        suspend fun EntryDetailsBehavior.blockThenLongPress(): Pair<List<Long>, Set<Long>> =
            withTimeout(30_000) {
                val loaded = state.filterIsInstance<EntryDetailsScreenState.Loaded>()
                val shown = loaded.first { page ->
                    page.chapters.items.count { it is EntryChapterListItem.Chapter } == 3
                }.chapters.items.mapNotNull { (it as? EntryChapterListItem.Chapter)?.id }
                toggleChapterBlock(listOf(shown.first()))
                loaded.first { it.selection == setOf(shown.first()) }
                toggleSelection(shown.last(), fromLongPress = true, shown = shown)
                shown to loaded.first { shown.last() in it.selection }.selection
            }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun blockThenLongPress(scope: TestScope): Pair<List<Long>, Set<Long>> {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            driver.seedManga(ENTRY, chapterId = 10L)
            listOf(11L, 12L).forEach { id ->
                driver.execute(
                    null,
                    "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                        "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                        "remote_date_upload, remote_memo) VALUES ($id, $ENTRY, '/$id', '$id', NULL, 0, 0, 0, " +
                        "${id - 9}, ${id - 9}, 0, 0, '{}')",
                    0,
                ).await()
            }
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                ENTRY,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(ENTRY),
            ).also { store.put("manga", it) }
            return try {
                MangaEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking())
                    .blockThenLongPress()
            } finally {
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }

        private companion object {
            const val ENTRY = 1L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun blockThenLongPress(scope: TestScope): Pair<List<Long>, Set<Long>> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                (1..3).forEach { harness.chapter(novel, it.toDouble()) }
                NovelEntryAdapter(
                    harness.openDetails(novel),
                    mockk(relaxed = true),
                    noDuplicateMarking(),
                    noVolumePicking(),
                )
                    .blockThenLongPress()
            }
    }
}
