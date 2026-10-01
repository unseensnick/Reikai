package reikai.domain.novel.interactor

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.effectiveHideChapterTitles
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

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
                Novel.create().copy(url = "/$it", favoriteAt = if (it < 4) 0L else null, chapterFlags = sorted),
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
