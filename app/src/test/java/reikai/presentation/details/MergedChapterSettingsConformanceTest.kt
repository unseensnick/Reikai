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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.novel.model.NovelChapterFlags
import reikai.presentation.novel.details.NovelDetailsViewModel
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
 * A merged series shows one chapter setting, its settings owner's, whichever member its details page is
 * opened through, through each type's real details model and adapter. The setting here is the
 * chapter-number display, the one the neutral state carries. [OWNER] is first in the group and shows
 * numbers; [SIBLING] is second and stores its own, names.
 */
class MergedChapterSettingsConformanceTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
        // Both read through Injekt in production; held at their shipped defaults.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter(any()) } returns TriState.DISABLED
        mockkObject(DebugToggles.ENABLE_EXH_ROOT_REDIRECT)
        every { DebugToggles.ENABLE_EXH_ROOT_REDIRECT.enabled } returns DebugToggles.ENABLE_EXH_ROOT_REDIRECT.default
        // A manga chip's header names its source through the language preferences, read off Injekt.
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
    fun `opened through a sibling, All shows the owner's setting`(type: ContentType) = runTest {
        group(type) { open(SIBLING).settled().showChapterNumberOnly } shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a source chip shows the owner's setting, not the opened member's`(type: ContentType) = runTest {
        group(type) {
            // A chip is picked from the switcher the merged page shows.
            val page = open(SIBLING).apply { settled() }
            page.selectSource(OWNER)
            page.settled(chip = OWNER).showChapterNumberOnly
        } shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a setting changed through a sibling is what opening through the owner shows`(type: ContentType) = runTest {
        group(type, ownerShowsNumbers = false) {
            // A page changes its setting once it has loaded one.
            open(SIBLING).apply { settled() }.showNumbers()
            open(OWNER).settled { it.showChapterNumberOnly }.showChapterNumberOnly
        } shouldBe true
    }

    /** Two settings picked before the first one's write reaches the page both land, each over the other. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `two settings picked at once both land`(type: ContentType) = runTest {
        group(type, ownerShowsNumbers = false) {
            open(SIBLING).apply { settled() }.apply {
                showNumbers()
                filterUnread()
            }
            open(OWNER).settled { it.showChapterNumberOnly }.showChapterNumberOnly to
                ownerFiltersOnceSettled(expected = 1)
        } shouldBe (true to setOf(UNREAD))
    }

    /** Each filter row writes the whole filter word, so a second pick must not put back the first one's. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `two filters picked at once both land`(type: ContentType) = runTest {
        group(type) {
            open(SIBLING).apply { settled() }.apply {
                filterUnread()
                filterBookmarked()
            }
            ownerFiltersOnceSettled(expected = 2)
        } shouldBe setOf(UNREAD, BOOKMARKED)
    }

    /** A pick through a sibling writes the owner's row last, so the page has to see that write on its own. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `opened through a sibling, a later change to the owner's setting shows`(type: ContentType) = runTest {
        group(type, ownerShowsNumbers = false) {
            val page = open(SIBLING).apply { settled() }
            ownerShowsNumbers()
            page.settled { it.showChapterNumberOnly }.showChapterNumberOnly
        } shouldBe true
    }

    /** One details page of the group, through its type's model. */
    private interface Page {
        val behavior: EntryDetailsBehavior
        fun selectSource(id: Long)
        fun showNumbers()
        fun filterUnread()
        fun filterBookmarked()
    }

    /** The group [OWNER], [SIBLING]: its details page opened through a member, and the owner's stored row. */
    private interface Group {
        suspend fun open(id: Long): Page

        /** Writes the number display to the owner's row alone, as the last write of a pick through a sibling. */
        suspend fun ownerShowsNumbers()

        /** Which of the unread and bookmarked filters the owner's stored setting has on. */
        suspend fun ownerFilters(): Set<String>
    }

    /** [Group.ownerFilters] once two quick picks have had time to land; the writes run on the app scope. */
    private suspend fun Group.ownerFiltersOnceSettled(expected: Int): Set<String> {
        withTimeoutOrNull(SETTLE_MS) { while (ownerFilters().size < expected) delay(20) }
        delay(SETTLE_GRACE_MS)
        return ownerFilters()
    }

    private class MangaPage(private val model: MangaViewModel) : Page {
        override val behavior = MangaEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking())
        override fun selectSource(id: Long) = model.selectSource(id)
        override fun showNumbers() = model.setDisplayMode(Manga.CHAPTER_DISPLAY_NUMBER)
        override fun filterUnread() = model.setUnreadFilter(TriState.ENABLED_IS)
        override fun filterBookmarked() = model.setBookmarkedFilter(TriState.ENABLED_IS)
    }

    private class NovelPage(private val model: NovelDetailsViewModel) : Page {
        override val behavior = NovelEntryAdapter(model, mockk(relaxed = true), noDuplicateMarking(), noVolumePicking())
        override fun selectSource(id: Long) = model.selectSource(id)
        override fun showNumbers() = model.setHideChapterTitles(true)
        override fun filterUnread() = model.setReadFilter(NovelChapterFlags.SHOW_UNREAD)
        override fun filterBookmarked() = model.setBookmarkedFilter(NovelChapterFlags.SHOW_BOOKMARKED)
    }

    /** The merged page once loaded, on [chip] (null = All) and once [until] holds. A chip counts once its
     *  member is the one viewed: the picked id reaches the state before the chip's own rows do. */
    private suspend fun Page.settled(
        chip: Long? = null,
        until: (EntryDetailsScreenState.Loaded) -> Boolean = { true },
    ): EntryDetailsScreenState.Loaded = withTimeout(10_000) {
        behavior.state.first {
            it is EntryDetailsScreenState.Loaded && it.isMerged && it.selectedSourceId == chip &&
                (chip == null || it.viewedEntryId == chip) && until(it)
        } as EntryDetailsScreenState.Loaded
    }

    /** [block] over the group [OWNER], [SIBLING], with a way to open its details page through a member. */
    private suspend fun <T> TestScope.group(
        type: ContentType,
        ownerShowsNumbers: Boolean = true,
        block: suspend Group.() -> T,
    ): T = when (type) {
        ContentType.MANGA -> mangaGroup(ownerShowsNumbers, block)
        else -> novelGroup(ownerShowsNumbers, block)
    }

    private suspend fun <T> mangaGroup(ownerShowsNumbers: Boolean, block: suspend Group.() -> T): T {
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
            val group = object : Group {
                override suspend fun open(id: Long): Page {
                    val chapters = ChapterRepositoryImpl(database)
                    val model = mangaDetailsModel(id, mangas, chapters, longArrayOf(OWNER, SIBLING))
                    store.put("manga$id", model)
                    models += model
                    return MangaPage(model)
                }

                override suspend fun ownerShowsNumbers() {
                    val flags = mangas.getMangaById(OWNER).chapterFlags
                    mangas.update(MangaUpdate(OWNER) { chapterFlags = flags or Manga.CHAPTER_DISPLAY_NUMBER })
                }

                override suspend fun ownerFilters(): Set<String> = mangas.getMangaById(OWNER).let {
                    setOfNotNull(
                        UNREAD.takeIf { _ -> it.unreadFilterRaw == Manga.CHAPTER_SHOW_UNREAD },
                        BOOKMARKED.takeIf { _ -> it.bookmarkedFilterRaw == Manga.CHAPTER_SHOW_BOOKMARKED },
                    )
                }
            }
            return group.block()
        } finally {
            // The models stop reading before the database closes.
            val jobs = models.map { it.viewModelScope.coroutineContext.job }
            store.clear()
            jobs.forEach { it.join() }
            driver.close()
        }
    }

    private suspend fun <T> TestScope.novelGroup(
        ownerShowsNumbers: Boolean,
        block: suspend Group.() -> T,
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
        val group = object : Group {
            override suspend fun open(id: Long): Page = NovelPage(harness.openDetails(id))

            override suspend fun ownerShowsNumbers() {
                val flags = harness.chapterFlagsOf(OWNER) and NovelChapterFlags.DISPLAY_MASK.inv()
                harness.updateNovel(OWNER) { chapterFlags = flags or NovelChapterFlags.DISPLAY_NUMBER }
            }

            override suspend fun ownerFilters(): Set<String> = harness.chapterFlagsOf(OWNER).let { flags ->
                setOfNotNull(
                    UNREAD.takeIf { flags and NovelChapterFlags.READ_MASK == NovelChapterFlags.SHOW_UNREAD },
                    BOOKMARKED.takeIf {
                        flags and NovelChapterFlags.BOOKMARKED_MASK == NovelChapterFlags.SHOW_BOOKMARKED
                    },
                )
            }
        }
        group.block()
    }

    private companion object {
        const val OWNER = 1L
        const val SIBLING = 2L
        const val UNREAD = "unread"
        const val BOOKMARKED = "bookmarked"
        const val SETTLE_MS = 5_000L

        // Long enough for a second write to land after the first, had it been going to undo it.
        const val SETTLE_GRACE_MS = 300L
        const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"
        const val SOURCE_EXTENSIONS_FILE = "eu.kanade.tachiyomi.source.SourceExtensionsKt"
    }
}
