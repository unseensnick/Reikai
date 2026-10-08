package reikai.domain.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.ui.manga.seedManga
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaChapterSettings
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.SetNovelChapterFlags
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.chapter.interactor.SetMangaDefaultChapterFlags
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.SetMangaChapterFlags
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * A merged series shares one chapter setting, its settings owner's, whichever member it is opened
 * through. One suite over both content types, each through its own [GroupChapterSettings] and the real
 * setter and library adder its screens call, over a real database. [OWNER] is first in the group's
 * order, [SIBLING] second.
 */
class GroupChapterSettingsConformanceTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = DatabaseBindings.providesDatabase(driver)

    @AfterEach
    fun tearDown() = driver.close()

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `opening through a sibling shows the owner's sort`(type: ContentType) = runTest {
        val side = side(type)

        side.shownDescending(SIBLING, GROUP) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a sort changed through a sibling is what opening through the owner shows`(type: ContentType) = runTest {
        val side = side(type)

        side.flipSortThrough(SIBLING, GROUP)

        side.shownDescending(OWNER, GROUP) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a sort changed through the owner is stored on every member`(type: ContentType) = runTest {
        val side = side(type)

        // Flipped back to descending, so the sibling's own stored ascending sort cannot pass for it.
        side.flipSortThrough(OWNER, GROUP)
        side.flipSortThrough(OWNER, GROUP)

        side.shownDescending(SIBLING, listOf(SIBLING)) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merge gives the members joining the owner's sort`(type: ContentType) = runTest {
        val side = side(type)

        side.settings.adoptOwnerSetting(GROUP)

        side.shownDescending(SIBLING, listOf(SIBLING)) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an entry outside a group shows its own sort`(type: ContentType) = runTest {
        val side = side(type)

        side.shownDescending(SIBLING, listOf(SIBLING)) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `reordering the sources moves the owner`(type: ContentType) = runTest {
        val side = side(type)

        side.shownDescending(OWNER, listOf(SIBLING, OWNER)) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a member added back after a change it missed takes the group's sort`(type: ContentType) = runTest {
        val side = side(type).apply { missAChangeOutsideTheLibrary() }

        side.addBack(OWNER)

        side.shownDescending(SIBLING, side.groupOf(SIBLING)) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a member added back into its group after a change it missed takes the group's sort`(type: ContentType) =
        runTest {
            val side = side(type).apply { missAChangeOutsideTheLibrary() }

            side.addBackIntoGroupOf(OWNER, SIBLING)

            side.shownDescending(SIBLING, side.groupOf(SIBLING)) shouldBe false
        }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a member added back into its group leaves the others storing the group's sort`(type: ContentType) =
        runTest {
            val side = side(type).apply { missAChangeOutsideTheLibrary() }

            side.addBackIntoGroupOf(OWNER, SIBLING)

            side.shownDescending(SIBLING, listOf(SIBLING)) shouldBe false
        }

    /** Merges the group, takes [OWNER] out of the library, and flips the sort through [SIBLING] to ascending. */
    private suspend fun Side.missAChangeOutsideTheLibrary() {
        GROUP.forEach { setInLibrary(it, true) }
        mergeManager.merge(GROUP)
        setInLibrary(OWNER, false)
        flipSortThrough(SIBLING, groupOf(SIBLING))
    }

    /** [OWNER] sorted descending and [SIBLING] ascending, each with its own setting. */
    private suspend fun side(type: ContentType): Side {
        Database.Schema.create(driver).await()
        return when (type) {
            ContentType.MANGA -> MangaSide().apply { seed(OWNER, descending = true) }.apply { seed(SIBLING, false) }
            else -> NovelSide().apply { seed(OWNER, descending = true) }.apply { seed(SIBLING, false) }
        }
    }

    private interface Side {
        val settings: GroupChapterSettings<*>
        val mergeManager: EntryMergeManager
        suspend fun seed(id: Long, descending: Boolean)
        suspend fun shownDescending(openedId: Long, memberIds: List<Long>): Boolean

        /** Picks the sort already shown, which flips its direction, as the details sort page does. */
        suspend fun flipSortThrough(openedId: Long, memberIds: List<Long>)

        suspend fun setInLibrary(id: Long, inLibrary: Boolean)

        /** Adds [id] back through a stored row's add confirm, which every add surface ends in. */
        suspend fun addBack(id: Long)

        /** Adds [id] back merged into [pickedId]'s group, as the duplicate prompt's group add confirms. */
        suspend fun addBackIntoGroupOf(id: Long, pickedId: Long)

        suspend fun groupOf(id: Long) = mergeManager.computeRelatedIds(id).asList()
    }

    private val reikaiPreferences = ReikaiLibraryPreferences(
        InMemoryPreferenceStore(sequenceOf(InMemoryPreference("series_merging_enabled", true, true))),
    )
    private val groups = MergeGroupRepositoryImpl(database)

    private inner class MangaSide : Side {
        private val mangas = MangaRepositoryImpl(database)
        private val setFlags = SetMangaChapterFlags(mangas)
        override val settings = MangaChapterSettings(mangas)
        override val mergeManager = MangaMergeManager(groups, reikaiPreferences, settings::adoptOwnerSetting) {}
        private val libraryPreferences = LibraryPreferences(InMemoryPreferenceStore())
        private val adder = MangaLibraryAdder(
            sourceManager = mockk(relaxed = true),
            libraryPreferences = libraryPreferences,
            getCategories = mockk(relaxed = true),
            getDuplicateLibraryManga = mockk(relaxed = true),
            getManga = GetManga(mangas),
            setMangaCategories = mockk(relaxed = true),
            setMangaDefaultChapterFlags = SetMangaDefaultChapterFlags(
                libraryPreferences,
                setFlags,
                GetFavorites(mangas),
            ),
            updateManga = UpdateManga(mangas, fetchInterval = mockk(), sourceTracker = mockk(relaxed = true)),
            autoBindOnAdd = mockk(relaxed = true),
            mergeManager = mergeManager,
            transactions = PassThroughTransactions,
            reikaiLibraryPreferences = reikaiPreferences,
            removeMangaFromLibrary = mockk(relaxed = true),
            chapterSettings = settings,
        )

        override suspend fun seed(id: Long, descending: Boolean) {
            driver.seedManga(id, chapterId = id * 10)
            val direction = if (descending) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC
            mangas.update(MangaUpdate(id) { chapterFlags = direction })
        }

        override suspend fun setInLibrary(id: Long, inLibrary: Boolean) {
            mangas.update(MangaUpdate(id) { favoriteAt = 1L.takeIf { inLibrary } })
        }

        override suspend fun addBack(id: Long) {
            adder.confirmAddCategories(id, categoryIds = emptyList())
        }

        override suspend fun addBackIntoGroupOf(id: Long, pickedId: Long) {
            adder.confirmGroupCategories(mangas.getMangaById(id), listOf(pickedId), categoryIds = emptyList())
        }

        private suspend fun shown(openedId: Long, memberIds: List<Long>) =
            settings.shown(mangas.getMangaById(openedId), memberIds)

        override suspend fun shownDescending(openedId: Long, memberIds: List<Long>) =
            shown(openedId, memberIds).sortDescending()

        override suspend fun flipSortThrough(openedId: Long, memberIds: List<Long>) {
            val shown = shown(openedId, memberIds)
            settings.change(openedId, memberIds) { setFlags.awaitSetSortingModeOrFlipOrder(shown, shown.sorting) }
        }
    }

    private inner class NovelSide : Side {
        private val novels = NovelRepositoryImpl(database)
        private val preferences = NovelPreferences(InMemoryPreferenceStore())
        private val setFlags = SetNovelChapterFlags(novels, preferences)
        override val settings = NovelChapterSettings(novels)
        override val mergeManager = NovelMergeManager(groups, reikaiPreferences, settings::adoptOwnerSetting) {}
        private val adder = NovelLibraryAdder(
            novelRepository = novels,
            manager = mockk(relaxed = true),
            getNovelCategories = mockk(relaxed = true),
            setNovelCategories = mockk(relaxed = true),
            updateNovel = UpdateNovel(novels, sourceTracker = mockk(relaxed = true)),
            novelPreferences = preferences,
            mergeManager = mergeManager,
            transactions = PassThroughTransactions,
            reikaiLibraryPreferences = reikaiPreferences,
            autoBindOnAdd = mockk(relaxed = true),
            removeNovelsFromLibrary = mockk(relaxed = true),
            chapterSettings = settings,
        )

        override suspend fun setInLibrary(id: Long, inLibrary: Boolean) {
            novels.update(NovelUpdate(id) { favoriteAt = 1L.takeIf { inLibrary } })
        }

        override suspend fun addBack(id: Long) {
            adder.confirmAddCategories(id, categoryIds = emptyList())
        }

        override suspend fun addBackIntoGroupOf(id: Long, pickedId: Long) {
            adder.confirmGroupCategories(id, listOf(pickedId), categoryIds = emptyList())
        }

        override suspend fun seed(id: Long, descending: Boolean) {
            val direction = if (descending) NovelChapterFlags.SORT_DESC else NovelChapterFlags.SORT_ASC
            val stored = novels.insert(
                Novel.create().copy(
                    source = "src$id",
                    url = "/novel/$id",
                    title = "Novel",
                    chapterFlags = direction or NovelChapterFlags.SORT_LOCAL,
                ),
            )
            stored shouldBe id
        }

        private suspend fun shown(openedId: Long, memberIds: List<Long>) =
            settings.shown(novels.getById(openedId)!!, memberIds)

        override suspend fun shownDescending(openedId: Long, memberIds: List<Long>) =
            shown(openedId, memberIds).effectiveSortDescending(preferences)

        override suspend fun flipSortThrough(openedId: Long, memberIds: List<Long>) {
            val shown = shown(openedId, memberIds)
            settings.change(openedId, memberIds) {
                setFlags.awaitSetSortingModeOrFlipOrder(shown, shown.effectiveSorting(preferences))
            }
        }
    }

    private companion object {
        const val OWNER = 1L
        const val SIBLING = 2L
        val GROUP = listOf(OWNER, SIBLING)
    }
}
