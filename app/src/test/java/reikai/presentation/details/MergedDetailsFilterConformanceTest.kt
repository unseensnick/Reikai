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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.library.ContentType
import reikai.domain.merge.ChapterUnit
import reikai.domain.novel.model.NovelChapterFlags
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * A merged series' details list shows one row per chapter, drawn read or bookmarked when any source's copy
 * is, so its chapter filters answer on that same group-wide flag, through each type's real details model
 * and adapter. The group is an owner, whose copy is the one shown and is unread and unbookmarked, and a
 * sibling, whose copy of the same chapter carries the flag under test. The readers' half of the rule is
 * MergedChapterFilterConformanceTest.
 */
class MergedDetailsFilterConformanceTest {

    enum class Filter { UNREAD, READ, BOOKMARKED, NOT_BOOKMARKED }

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
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
        resetVirtualDispatchers()
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("cases")
    fun `a filter answers on the flag another source's copy carries`(
        type: ContentType,
        filter: Filter,
        readElsewhere: Boolean,
        bookmarkedElsewhere: Boolean,
        shown: Boolean,
    ) = runTest {
        shownRows(type, filter, readElsewhere, bookmarkedElsewhere) shouldBe if (shown) 1 else 0
    }

    /** How many rows the All view lists once settled under [filter]. */
    private suspend fun TestScope.shownRows(
        type: ContentType,
        filter: Filter,
        readElsewhere: Boolean,
        bookmarkedElsewhere: Boolean,
    ): Int = when (type) {
        ContentType.MANGA -> mangaRows(filter, readElsewhere, bookmarkedElsewhere)
        else -> novelRows(filter, readElsewhere, bookmarkedElsewhere)
    }

    private suspend fun TestScope.mangaRows(filter: Filter, readElsewhere: Boolean, bookmarkedElsewhere: Boolean): Int {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        val mangas = MangaRepositoryImpl(database)
        driver.seedManga(OWNER, chapterId = OWNER_COPY, sourceId = OWNER)
        driver.seedManga(SIBLING, chapterId = SIBLING_COPY, sourceId = SIBLING)
        driver.execute(
            null,
            "UPDATE chapter SET user_read = ${readElsewhere.bit}, user_bookmark = ${bookmarkedElsewhere.bit} " +
                "WHERE id = $SIBLING_COPY",
            0,
        ).await()
        mangas.update(MangaUpdate(OWNER) { chapterFlags = mangaFlagOf(filter) })
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            OWNER,
            mangas,
            ChapterRepositoryImpl(database),
            longArrayOf(OWNER, SIBLING),
            stitch = listOf(ChapterUnit(OWNER_COPY, 0, 0), ChapterUnit(SIBLING_COPY, 0, 1)),
        ).also { store.put("manga", it) }
        try {
            return rowsOf(MangaEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking()))
        } finally {
            // The model stops reading before the database closes.
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            job.join()
            driver.close()
        }
    }

    private suspend fun TestScope.novelRows(filter: Filter, readElsewhere: Boolean, bookmarkedElsewhere: Boolean) =
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val owner = harness.novel(harness.source("owner"))
            val sibling = harness.novel(harness.source("sibling"))
            // Titled, so the stitch pairs the two copies; the owner has the lower id, so its copy is shown.
            harness.chapter(owner, 1.0, name = "Opening")
            harness.chapter(sibling, 1.0, read = readElsewhere, bookmark = bookmarkedElsewhere, name = "Opening")
            harness.updateNovel(owner) {
                chapterFlags = NovelChapterFlags.FILTER_LOCAL or NovelChapterFlags.SORT_LOCAL or novelFlagOf(filter)
            }
            harness.merge(owner, sibling)
            rowsOf(
                NovelEntryAdapter(
                    harness.openDetails(owner),
                    mockk(relaxed = true),
                    noDuplicateMarking(),
                    noVolumePicking(),
                ),
            )
        }

    /** The merged All view's chapter rows once everything it started has run. */
    private suspend fun TestScope.rowsOf(behavior: EntryDetailsBehavior): Int {
        behavior.state.first { it is EntryDetailsScreenState.Loaded && it.isMerged }
        advanceUntilIdle()
        val loaded = behavior.state.value as EntryDetailsScreenState.Loaded
        return loaded.chapters.items.count { it is EntryChapterListItem.Chapter }
    }

    companion object {
        private const val OWNER = 1L
        private const val SIBLING = 2L
        private const val OWNER_COPY = 10L
        private const val SIBLING_COPY = 20L
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        private const val SOURCE_EXTENSIONS_FILE = "eu.kanade.tachiyomi.source.SourceExtensionsKt"

        private val Boolean.bit get() = if (this) 1 else 0

        @JvmStatic
        fun cases() = listOf(ContentType.MANGA, ContentType.NOVELS).flatMap { type ->
            listOf(
                Arguments.of(type, Filter.UNREAD, true, false, false),
                Arguments.of(type, Filter.READ, true, false, true),
                Arguments.of(type, Filter.BOOKMARKED, false, true, true),
                Arguments.of(type, Filter.NOT_BOOKMARKED, false, true, false),
            )
        }

        fun mangaFlagOf(filter: Filter) = when (filter) {
            Filter.UNREAD -> Manga.CHAPTER_SHOW_UNREAD
            Filter.READ -> Manga.CHAPTER_SHOW_READ
            Filter.BOOKMARKED -> Manga.CHAPTER_SHOW_BOOKMARKED
            Filter.NOT_BOOKMARKED -> Manga.CHAPTER_SHOW_NOT_BOOKMARKED
        }

        fun novelFlagOf(filter: Filter) = when (filter) {
            Filter.UNREAD -> NovelChapterFlags.SHOW_UNREAD
            Filter.READ -> NovelChapterFlags.SHOW_READ
            Filter.BOOKMARKED -> NovelChapterFlags.SHOW_BOOKMARKED
            Filter.NOT_BOOKMARKED -> NovelChapterFlags.SHOW_NOT_BOOKMARKED
        }
    }
}
