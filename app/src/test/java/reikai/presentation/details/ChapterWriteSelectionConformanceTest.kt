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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.model.Manga

/**
 * A read or bookmark write on one chapter ends the selection, as the selection bar's own do, through
 * each type's real details model and adapter. Pinned once over both types.
 */
class ChapterWriteSelectionConformanceTest {

    /** A write on one chapter that is not the selected one. */
    enum class Write(val run: EntryDetailsBehavior.(Long) -> Unit) {
        HeaderMarkRead({ markChaptersRead(listOf(it), read = true) }),
        ReadSwipe({ chapterSwipe(it, LibraryPreferences.ChapterSwipeAction.ToggleRead) }),
        BookmarkSwipe({ chapterSwipe(it, LibraryPreferences.ChapterSwipeAction.ToggleBookmark) }),
    }

    /** A two-chapter entry with the first chapter selected. */
    interface Probe {
        /** The selection once [write] runs on the second chapter. */
        suspend fun selectionAfter(scope: TestScope, write: Write): Set<Long>
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Both read through Injekt in production; held at their shipped defaults.
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
        Dispatchers.resetMain()
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    fun `a write on one chapter ends the selection`(probe: Probe, write: Write) = runTest {
        probe.selectionAfter(this, write) shouldBe emptySet()
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun selectionAfter(scope: TestScope, write: Write): Set<Long> {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            driver.seedManga(ENTRY, chapterId = FIRST)
            driver.execute(
                null,
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES ($SECOND, $ENTRY, '/$SECOND', '$SECOND', " +
                    "NULL, 0, 0, 0, 2, 2, 0, 0, '{}')",
                0,
            ).await()
            val store = ViewModelStore()
            val model = mangaDetailsModel(
                ENTRY,
                MangaRepositoryImpl(database),
                ChapterRepositoryImpl(database),
                longArrayOf(ENTRY),
            ).also { store.put("manga", it) }
            return try {
                MangaEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking())
                    .settledSelection(FIRST, SECOND, write)
            } finally {
                // The model reads on the real IO dispatcher, so it stops before the database closes.
                val job = model.viewModelScope.coroutineContext.job
                store.clear()
                job.join()
                driver.close()
            }
        }

        private companion object {
            const val ENTRY = 1L
            const val FIRST = 10L
            const val SECOND = 11L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun selectionAfter(scope: TestScope, write: Write): Set<Long> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val first = harness.chapter(novel, 1.0).id
                val second = harness.chapter(novel, 2.0).id
                NovelEntryAdapter(
                    harness.openDetails(novel),
                    mockk(relaxed = true),
                    noDuplicateMarking(),
                    noVolumePicking(),
                )
                    .settledSelection(first, second, write)
            }
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val SOURCE_EXTENSIONS_FILE = "eu.kanade.tachiyomi.source.SourceExtensionsKt"

        @JvmStatic
        fun cases() = listOf(MangaProbe(), NovelProbe()).flatMap { probe ->
            Write.entries.map { Arguments.of(probe, it) }
        }

        /** Selects [selected], runs [write] on [written], and reads the selection once it settles. */
        suspend fun EntryDetailsBehavior.settledSelection(selected: Long, written: Long, write: Write): Set<Long> =
            withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    val loaded = state.filterIsInstance<EntryDetailsScreenState.Loaded>()
                    val shown = loaded.first { page ->
                        page.chapters.items.count { it is EntryChapterListItem.Chapter } ==
                            2
                    }
                        .chapters.items.mapNotNull { (it as? EntryChapterListItem.Chapter)?.id }
                    toggleSelection(selected, fromLongPress = false, shown = shown)
                    loaded.first { it.selection == setOf(selected) }
                    write.run(this@settledSelection, written)
                    withTimeoutOrNull(5_000) { loaded.first { it.selection.isEmpty() } }?.selection
                        ?: loaded.first().selection
                }
            }
    }
}
