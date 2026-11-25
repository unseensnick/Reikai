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
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.chapter.hiddenChapterKey
import reikai.domain.library.ContentType
import reikai.domain.merge.ChapterUnit
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
 * A chapter hidden on a merged series is keyed by the source of the member whose copy it is, so a
 * series page's Resume passes over it, through each type's real details model and adapter. The sibling's
 * chapter is the one read first and is hidden; the next one is where Resume should open.
 */
class MergedHiddenResumeConformanceTest {

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

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a chapter hidden on another source of a merged series is passed over by Resume`(type: ContentType) =
        runTest {
            when (type) {
                ContentType.MANGA -> mangaResume() shouldBe OWNER_COPY
                else -> novelResume()
            }
        }

    private suspend fun TestScope.mangaResume(): Long? {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        val mangas = MangaRepositoryImpl(database)
        driver.seedManga(OWNER, chapterId = OWNER_COPY, sourceId = OWNER)
        driver.seedManga(SIBLING, chapterId = SIBLING_COPY, sourceId = SIBLING)
        driver.execute(null, "UPDATE chapter SET remote_chapter_number = 2.0 WHERE id = $OWNER_COPY", 0).await()
        val prefs = InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    "manga_hidden_chapters",
                    setOf(hiddenChapterKey(SIBLING.toString(), "/$SIBLING_COPY")),
                    emptySet<String>(),
                ),
            ),
        )
        val store = ViewModelStore()
        val model = mangaDetailsModel(
            OWNER,
            mangas,
            ChapterRepositoryImpl(database),
            longArrayOf(OWNER, SIBLING),
            stitch = listOf(ChapterUnit(SIBLING_COPY, 0, 0), ChapterUnit(OWNER_COPY, 1, 0)),
            prefs = prefs,
        ).also { store.put("manga", it) }
        try {
            return resumeOf(MangaEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking()))
        } finally {
            // The model stops reading before the database closes.
            val job = model.viewModelScope.coroutineContext.job
            store.clear()
            job.join()
            driver.close()
        }
    }

    private suspend fun TestScope.novelResume() =
        NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val owner = harness.novel(harness.source("owner"))
            val sibling = harness.novel(harness.source("sibling"))
            val shown = harness.chapter(owner, 2.0, name = "Second")
            val hidden = harness.chapter(sibling, 1.0, name = "First")
            // Paired with the owner's copy, so the stitch lists the sibling's First ahead of it.
            val shownElsewhere = harness.chapter(sibling, 2.0, name = "Second")
            harness.novelPreferences.hiddenChapters().set(setOf(hiddenChapterKey("sibling", hidden.url)))
            harness.merge(owner, sibling)
            val resume = resumeOf(
                NovelEntryAdapter(
                    harness.openDetails(owner),
                    mockk(relaxed = true),
                    noDuplicateMarking(),
                    noVolumePicking(),
                ),
            )
            // Either copy of Second is that chapter; which one opens is the preferred source's rule, not this one's.
            (resume in setOf(shown.id, shownElsewhere.id)) shouldBe true
        }

    /** The chapter the merged All view's Resume opens once everything it started has run. */
    private suspend fun TestScope.resumeOf(behavior: EntryDetailsBehavior): Long? {
        behavior.state.first { it is EntryDetailsScreenState.Loaded && it.isMerged }
        advanceUntilIdle()
        return (behavior.state.value as EntryDetailsScreenState.Loaded).resumeChapterId
    }

    private companion object {
        const val OWNER = 1L
        const val SIBLING = 2L
        const val OWNER_COPY = 10L
        const val SIBLING_COPY = 20L
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        const val SOURCE_EXTENSIONS_FILE = "eu.kanade.tachiyomi.source.SourceExtensionsKt"
    }
}
