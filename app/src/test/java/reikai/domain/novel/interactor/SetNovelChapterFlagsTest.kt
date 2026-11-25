package reikai.domain.novel.interactor

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelChapterFlags.SHOW_BOOKMARKED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_DOWNLOADED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_NOT_BOOKMARKED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_NOT_DOWNLOADED
import reikai.domain.novel.model.NovelChapterFlags.SHOW_READ
import reikai.domain.novel.model.NovelChapterFlags.SHOW_UNREAD
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.effectiveBookmarkedFilter
import reikai.domain.novel.model.effectiveDownloadedFilter
import reikai.domain.novel.model.effectiveHideChapterTitles
import reikai.domain.novel.model.effectiveReadFilter
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import kotlin.time.Instant

/**
 * Title display and chapter sort each have their own local-override bit, so changing one on a novel
 * leaves the other following the global default. They used to share the sort bit, and hiding titles
 * switched a never-sorted novel to source order, descending.
 */
class SetNovelChapterFlagsTest {

    // Seeded through the constructor: InMemoryPreferenceStore never reflects a set() on the next read.
    private val prefs = NovelPreferences(
        InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference(
                    "ln_default_chapter_sort",
                    NovelChapterFlags.SORTING_NUMBER,
                    0L,
                ),
                InMemoryPreferenceStore.InMemoryPreference("ln_default_chapter_sort_desc", false, true),
                InMemoryPreferenceStore.InMemoryPreference("ln_default_chapter_hide_titles", true, false),
            ),
        ),
    )

    private suspend fun written(
        chapterFlags: Long = 0L,
        write: suspend SetNovelChapterFlags.(Novel) -> Unit,
    ): Novel {
        val sent = slot<NovelUpdate>()
        val repository = mockk<NovelRepository> { coEvery { update(capture(sent)) } returns true }
        val novel = Novel.create().copy(id = 1L, chapterFlags = chapterFlags)
        SetNovelChapterFlags(repository, prefs).write(novel)
        return novel.copy(chapterFlags = sent.captured.chapterFlags!!)
    }

    @Test
    fun `changing title display leaves the sort on the global default`() = runTest {
        val novel = written { awaitSetHideTitles(it, hide = false) }
        (novel.effectiveSorting(prefs) to novel.effectiveSortDescending(prefs)) shouldBe
            (NovelChapterFlags.SORTING_NUMBER to false)
    }

    @Test
    fun `changing the sort leaves title display on the global default`() = runTest {
        val novel = written { awaitSetSortingModeOrFlipOrder(it, NovelChapterFlags.SORTING_ALPHABET) }
        novel.effectiveHideChapterTitles(prefs) shouldBe true
    }

    @Test
    fun `a picked sort sticks`() = runTest {
        val novel = written { awaitSetSortingModeOrFlipOrder(it, NovelChapterFlags.SORTING_ALPHABET) }
        novel.effectiveSorting(prefs) shouldBe NovelChapterFlags.SORTING_ALPHABET
    }

    @Test
    fun `picking a new sort mode sorts ascending`() = runTest {
        val newestFirst =
            NovelChapterFlags.SORT_LOCAL or NovelChapterFlags.SORTING_NUMBER or NovelChapterFlags.SORT_DESC
        val novel = written(newestFirst) { awaitSetSortingModeOrFlipOrder(it, NovelChapterFlags.SORTING_ALPHABET) }
        novel.effectiveSortDescending(prefs) shouldBe false
    }

    // The novel's stored bits say source order, newest first; what it shows is the global number, ascending.
    @Test
    fun `a global-default novel flips the direction it shows`() = runTest {
        val novel = written { awaitSetSortingModeOrFlipOrder(it, NovelChapterFlags.SORTING_NUMBER) }
        novel.effectiveSortDescending(prefs) shouldBe true
    }

    @Test
    fun `a title display change sticks`() = runTest {
        val novel = written { awaitSetHideTitles(it, hide = false) }
        novel.effectiveHideChapterTitles(prefs) shouldBe false
    }

    @Test
    fun `clearing the local overrides returns title display to the global default`() = runTest {
        val displayed = Novel.create().copy(
            id = 1L,
            chapterFlags = NovelChapterFlags.DISPLAY_LOCAL or NovelChapterFlags.DISPLAY_NAME,
        )
        val sent = slot<NovelUpdate>()
        val repository = mockk<NovelRepository> { coEvery { update(capture(sent)) } returns true }
        SetNovelChapterFlags(repository, prefs).awaitClearLocalOverrides(displayed)
        displayed.copy(chapterFlags = sent.captured.chapterFlags!!).effectiveHideChapterTitles(prefs) shouldBe true
    }

    @Test
    fun `clearing the local overrides returns the chapter filter to the global default`() = runTest {
        val filtered = written(NovelChapterFlags.FILTER_LOCAL or NovelChapterFlags.SHOW_UNREAD) {
            awaitClearLocalOverrides(it)
        }

        filtered.effectiveReadFilter(prefs) shouldBe 0L
    }

    /** Which filter a pick sets, and the value it picks, against the defaults [filterDefaults] holds. */
    enum class FilterPick(
        val write: suspend SetNovelChapterFlags.(Novel) -> Unit,
        val shown: Triple<Long, Long, Long>,
    ) {
        READ(
            { awaitSetReadFilter(it, SHOW_UNREAD) },
            Triple(SHOW_UNREAD, SHOW_BOOKMARKED, SHOW_DOWNLOADED),
        ),
        BOOKMARKED(
            { awaitSetBookmarkedFilter(it, SHOW_NOT_BOOKMARKED) },
            Triple(SHOW_READ, SHOW_NOT_BOOKMARKED, SHOW_DOWNLOADED),
        ),
        DOWNLOADED(
            { awaitSetDownloadedFilter(it, SHOW_NOT_DOWNLOADED) },
            Triple(SHOW_READ, SHOW_BOOKMARKED, SHOW_NOT_DOWNLOADED),
        ),
    }

    // The three filters share one local bit, so a pick on a novel still following the defaults takes the other
    // two as it showed them, or setting one would turn the others off.
    @ParameterizedTest
    @EnumSource(FilterPick::class)
    fun `a filter pick keeps the other two filters as the novel showed them`(pick: FilterPick) = runTest {
        val sent = slot<NovelUpdate>()
        val repository = mockk<NovelRepository> { coEvery { update(capture(sent)) } returns true }
        val novel = Novel.create().copy(id = 1L)
        SetNovelChapterFlags(repository, filterDefaults).apply { pick.write(this, novel) }
        val stored = novel.copy(chapterFlags = sent.captured.chapterFlags!!)

        Triple(
            stored.effectiveReadFilter(filterDefaults),
            stored.effectiveBookmarkedFilter(filterDefaults),
            stored.effectiveDownloadedFilter(filterDefaults),
        ) shouldBe pick.shown
    }

    private val filterDefaults = NovelPreferences(
        InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreferenceStore.InMemoryPreference("ln_default_chapter_filter_unread", SHOW_READ, 0L),
                InMemoryPreferenceStore.InMemoryPreference(
                    "ln_default_chapter_filter_bookmarked",
                    SHOW_BOOKMARKED,
                    0L,
                ),
                InMemoryPreferenceStore.InMemoryPreference(
                    "ln_default_chapter_filter_downloaded",
                    SHOW_DOWNLOADED,
                    0L,
                ),
            ),
        ),
    )

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    /** Three library novels with their own sort, and one outside the library, over the real SQL. */
    private suspend fun sortedLibrary(): Pair<Database, NovelRepositoryImpl> {
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        val novels = NovelRepositoryImpl(database)
        val sorted = NovelChapterFlags.SORT_LOCAL or NovelChapterFlags.SORTING_ALPHABET
        (1..4).forEach {
            novels.insert(
                Novel.create().copy(
                    url = "/$it",
                    favoriteAt = if (it <
                        4
                    ) {
                        Instant.fromEpochMilliseconds(0L)
                    } else {
                        null
                    },
                    chapterFlags = sorted,
                ),
            )
        }
        return database to novels
    }

    @Test
    fun `applying the defaults to the library returns every library novel to them`() = runTest {
        val (_, novels) = sortedLibrary()

        SetNovelChapterFlags(novels, prefs).awaitClearLibraryLocalOverrides()

        novels.getAll().sortedBy { it.url }.map { it.effectiveSorting(prefs) } shouldBe listOf(
            NovelChapterFlags.SORTING_NUMBER,
            NovelChapterFlags.SORTING_NUMBER,
            NovelChapterFlags.SORTING_NUMBER,
            NovelChapterFlags.SORTING_ALPHABET,
        )
    }

    /** One write for the whole library, as manga's apply-to-library makes, so the library list redraws once. */
    @Test
    fun `applying the defaults to the library notifies the novel list once`() = runTest {
        val (database, novels) = sortedLibrary()
        var notifications = 0
        database.novelsQueries.findAll().addListener { notifications++ }

        SetNovelChapterFlags(novels, prefs).awaitClearLibraryLocalOverrides()

        notifications shouldBe 1
    }
}
