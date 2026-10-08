package reikai.presentation.details

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.getNameForMangaInfo
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.novel.model.NovelChapterFlags
import reikai.presentation.novel.details.NovelDetailsViewModel
import reikai.presentation.reader.NovelReaderViewModelHarness
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.chapter.ChapterRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * A merged series shows one chapter setting, its settings owner's, whichever member its details page is
 * opened through, through each type's real details model and adapter. The setting here is the
 * chapter-number display, the one the neutral state carries. [OWNER] is first in the group and shows
 * numbers; [SIBLING] is second and stores its own, names.
 */
class MergedChapterSettingsConformanceTest {

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // Both read through Injekt in production; held at their shipped defaults.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
        // A manga chip's header names its source through the language preferences, read off Injekt.
        mockkStatic(SOURCE_EXTENSIONS_FILE)
        every { any<Source>().getNameForMangaInfo() } returns "source"
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
        Dispatchers.resetMain()
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `opened through a sibling, All shows the owner's setting`(type: ContentType) = runTest {
        group(type) { open -> open(SIBLING).settled().showChapterNumberOnly } shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a source chip shows the owner's setting, not the opened member's`(type: ContentType) = runTest {
        group(type) { open ->
            // A chip is picked from the switcher the merged page shows.
            val page = open(SIBLING).apply { settled() }
            page.selectSource(OWNER)
            page.settled(chip = OWNER).showChapterNumberOnly
        } shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a setting changed through a sibling is what opening through the owner shows`(type: ContentType) = runTest {
        group(type, ownerShowsNumbers = false) { open ->
            // A page changes its setting once it has loaded one.
            open(SIBLING).apply { settled() }.showNumbers()
            open(OWNER).settled { it.showChapterNumberOnly }.showChapterNumberOnly
        } shouldBe true
    }

    /** One details page of the group, through its type's model. */
    private interface Page {
        val behavior: EntryDetailsBehavior
        fun selectSource(id: Long)
        fun showNumbers()
    }

    private class MangaPage(private val model: MangaViewModel) : Page {
        override val behavior = MangaEntryAdapter(model, mockk(relaxed = true))
        override fun selectSource(id: Long) = model.selectSource(id)
        override fun showNumbers() = model.setDisplayMode(Manga.CHAPTER_DISPLAY_NUMBER)
    }

    private class NovelPage(private val model: NovelDetailsViewModel) : Page {
        override val behavior = NovelEntryAdapter(model, mockk(relaxed = true))
        override fun selectSource(id: Long) = model.selectSource(id)
        override fun showNumbers() = model.setHideChapterTitles(true)
    }

    /** The merged page once loaded, on [chip] (null = All) and once [until] holds. A chip counts once its
     *  member is the one viewed: the picked id reaches the state before the chip's own rows do. */
    private suspend fun Page.settled(
        chip: Long? = null,
        until: (EntryDetailsScreenState.Loaded) -> Boolean = { true },
    ): EntryDetailsScreenState.Loaded = withContext(Dispatchers.Default) {
        withTimeout(10_000) {
            behavior.state.first {
                it is EntryDetailsScreenState.Loaded && it.isMerged && it.selectedSourceId == chip &&
                    (chip == null || it.viewedEntryId == chip) && until(it)
            } as EntryDetailsScreenState.Loaded
        }
    }

    /** [block] over the group [OWNER], [SIBLING], with a way to open its details page through a member. */
    private suspend fun <T> TestScope.group(
        type: ContentType,
        ownerShowsNumbers: Boolean = true,
        block: suspend (open: suspend (Long) -> Page) -> T,
    ): T = when (type) {
        ContentType.MANGA -> mangaGroup(ownerShowsNumbers, block)
        else -> novelGroup(ownerShowsNumbers, block)
    }

    private suspend fun <T> mangaGroup(ownerShowsNumbers: Boolean, block: suspend (suspend (Long) -> Page) -> T): T {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        val mangas = MangaRepositoryImpl(database)
        listOf(OWNER, SIBLING).forEach { driver.seedManga(it, chapterId = it * 10, sourceId = it) }
        val display = if (ownerShowsNumbers) Manga.CHAPTER_DISPLAY_NUMBER else Manga.CHAPTER_DISPLAY_NAME
        mangas.update(MangaUpdate(OWNER) { chapterFlags = display })
        val store = ViewModelStore()
        val models = mutableListOf<MangaViewModel>()
        try {
            return block { id ->
                val model = mangaDetailsModel(id, mangas, ChapterRepositoryImpl(database), longArrayOf(OWNER, SIBLING))
                store.put("manga$id", model)
                models += model
                MangaPage(model)
            }
        } finally {
            // The models read on the real IO dispatcher, so they stop before the database closes.
            val jobs = models.map { it.viewModelScope.coroutineContext.job }
            store.clear()
            jobs.forEach { it.join() }
            driver.close()
        }
    }

    private suspend fun <T> TestScope.novelGroup(
        ownerShowsNumbers: Boolean,
        block: suspend (suspend (Long) -> Page) -> T,
    ): T = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
        val ids = listOf(OWNER, SIBLING).map { harness.novel(harness.source("src$it")) }
        ids shouldBe listOf(OWNER, SIBLING)
        ids.forEach { harness.chapter(it, 1.0) }
        val display = if (ownerShowsNumbers) NovelChapterFlags.DISPLAY_NUMBER else NovelChapterFlags.DISPLAY_NAME
        harness.updateNovel(OWNER) { chapterFlags = display or NovelChapterFlags.DISPLAY_LOCAL }
        harness.updateNovel(SIBLING) {
            chapterFlags = NovelChapterFlags.DISPLAY_NAME or NovelChapterFlags.DISPLAY_LOCAL
        }
        harness.merge(OWNER, SIBLING)
        block { id -> NovelPage(harness.openDetails(id)) }
    }

    private companion object {
        const val OWNER = 1L
        const val SIBLING = 2L
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        const val SOURCE_EXTENSIONS_FILE = "eu.kanade.tachiyomi.source.SourceExtensionsKt"
    }
}
