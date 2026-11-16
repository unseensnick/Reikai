package reikai.domain.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.ui.manga.seedManga
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import mihon.app.di.ReikaiBindings
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaChapterSettings
import reikai.domain.manga.MangaGroupCategories
import reikai.domain.manga.MangaViewerFlagSettings
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelGroupCategories
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelViewerFlagSettings
import reikai.domain.novel.interactor.SetNovelChapterFlags
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapterFlags
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.effectiveSortDescending
import reikai.domain.novel.model.effectiveSorting
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.presentation.recents.EmittingPreferenceStore
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
import kotlin.time.Instant

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

    /** Two quick taps on the shown sort each flip it, the second from what the first wrote. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `two sort picks made at once both flip the sort`(type: ContentType) = runTest {
        val side = side(type)

        listOf(
            launch { side.flipSortThrough(OWNER, GROUP) { yield() } },
            launch { side.flipSortThrough(OWNER, GROUP) { yield() } },
        ).joinAll()

        side.shownDescending(OWNER, GROUP) shouldBe true
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
        val side = side(type).apply { GROUP.forEach { setInLibrary(it, true) } }

        side.mergeManager.merge(GROUP)

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

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a new entry joining an ungrouped pick takes the pick's sort`(type: ContentType) = runTest {
        val side = side(type).apply { pickInLibraryWithNewcomerOut() }

        side.addBackIntoGroupOf(OWNER, SIBLING)

        side.shownDescending(SIBLING, listOf(SIBLING)) shouldBe false
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a new entry joining an ungrouped pick leaves the pick the settings owner`(type: ContentType) = runTest {
        val side = side(type).apply { pickInLibraryWithNewcomerOut() }

        side.addBackIntoGroupOf(OWNER, SIBLING)

        side.groupOf(OWNER).first() shouldBe SIBLING
    }

    // A library entry added under the default keeps it when another entry's settings become the default,
    // until the change is applied to the library; a manga stores what it was added with.
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `setting another entry's sort as the default leaves a library entry's sort as it was`(type: ContentType) =
        runTest {
            val side = side(type).apply { addUnderTheDefault(OTHER) }

            side.setAsDefault(SIBLING, applyToLibrary = false)

            side.shownDescending(OTHER, listOf(OTHER)) shouldBe true
        }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `setting a sort as the default for the library moves every library entry to it`(type: ContentType) =
        runTest {
            val side = side(type).apply { addUnderTheDefault(OTHER) }

            side.setAsDefault(SIBLING, applyToLibrary = true)

            side.shownDescending(OTHER, listOf(OTHER)) shouldBe false
        }

    /** [SIBLING] in the library and ungrouped, [OWNER] not yet added: the add-to-group prompt's usual pick. */
    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merge gives the members joining the owner's reader flags`(type: ContentType) = runTest {
        val side = side(type).apply { GROUP.forEach { setInLibrary(it, true) } }

        side.mergeManager.merge(GROUP)

        side.viewerFlagsOf(SIBLING) shouldBe OWNER_ROTATION
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a member added back after a reader flag change it missed takes the group's flags`(type: ContentType) =
        runTest {
            val side = side(type).apply { missARotationOutsideTheLibrary() }

            side.addBack(OWNER)

            side.viewerFlagsOf(OWNER) shouldBe CHANGED_ROTATION
        }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a member added back into its group after a reader flag change it missed takes the group's flags`(
        type: ContentType,
    ) = runTest {
        val side = side(type).apply { missARotationOutsideTheLibrary() }

        side.addBackIntoGroupOf(OWNER, SIBLING)

        side.viewerFlagsOf(OWNER) shouldBe CHANGED_ROTATION
    }

    private suspend fun Side.pickInLibraryWithNewcomerOut() {
        setInLibrary(SIBLING, true)
        setInLibrary(OWNER, false)
    }

    /** Merges the group, takes [OWNER] out of the library, and flips the sort through [SIBLING] to ascending. */
    private suspend fun Side.missAChangeOutsideTheLibrary() {
        GROUP.forEach { setInLibrary(it, true) }
        mergeManager.merge(GROUP)
        setInLibrary(OWNER, false)
        flipSortThrough(SIBLING, groupOf(SIBLING))
    }

    /** As [missAChangeOutsideTheLibrary], with a rotation set through [SIBLING] as both readers set one. */
    private suspend fun Side.missARotationOutsideTheLibrary() {
        GROUP.forEach { setInLibrary(it, true) }
        mergeManager.merge(GROUP)
        setInLibrary(OWNER, false)
        GroupViewerFlags.of(SIBLING, groupOf(SIBLING)).write { setRotation(it, CHANGED_ROTATION) }
    }

    /** [OWNER] sorted descending and [SIBLING] ascending, each with its own setting and its own rotation. */
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

        /** Picks the sort already shown, which flips its direction, as the details sort page does. [beforeWrite]
         *  runs between reading what is shown and writing, where a second pick can land. */
        suspend fun flipSortThrough(openedId: Long, memberIds: List<Long>, beforeWrite: suspend () -> Unit = {})

        suspend fun setInLibrary(id: Long, inLibrary: Boolean)

        /** Adds [id] back through a stored row's add confirm, which every add surface ends in. */
        suspend fun addBack(id: Long)

        /** Adds [id] back merged into [pickedId]'s group, as the duplicate prompt's group add does. */
        suspend fun addBackIntoGroupOf(id: Long, pickedId: Long)

        /** Stores [id] with no chapter setting of its own and adds it, so it takes the default. */
        suspend fun addUnderTheDefault(id: Long)

        /** Makes [openedId]'s shown settings the default, as the details Set as default does. */
        suspend fun setAsDefault(openedId: Long, applyToLibrary: Boolean)

        suspend fun groupOf(id: Long) = mergeManager.computeRelatedIds(id).asList()

        suspend fun viewerFlagsOf(id: Long): Long

        /** The one masked write each reader makes per member when the rotation changes. */
        suspend fun setRotation(id: Long, flag: Long)
    }

    private val reikaiPreferences = ReikaiLibraryPreferences(
        InMemoryPreferenceStore(sequenceOf(InMemoryPreference("series_merging_enabled", true, true))),
    )
    private val groups = MergeGroupRepositoryImpl(database)

    private inner class MangaSide : Side {
        private val mangas = MangaRepositoryImpl(database)
        private val setFlags = SetMangaChapterFlags(mangas)
        override val settings = MangaChapterSettings(mangas)
        private val viewerFlags = MangaViewerFlagSettings(mangas)

        // Built as the graph builds it, so the merge hook the app runs is the one tested.
        override val mergeManager = ReikaiBindings.providesMangaMergeManager(
            groups,
            reikaiPreferences,
            propagate = mockk(relaxed = true),
            chapterSettings = settings,
            viewerFlagSettings = viewerFlags,
            groupCategories = MangaGroupCategories(mockk(relaxed = true), mockk(relaxed = true)),
        )
        private val libraryPreferences = LibraryPreferences(EmittingPreferenceStore())
        private val setDefaultFlags = SetMangaDefaultChapterFlags(libraryPreferences, setFlags, GetFavorites(mangas))
        private val adder = MangaLibraryAdder(
            sourceManager = mockk(relaxed = true),
            libraryPreferences = libraryPreferences,
            getCategories = mockk(relaxed = true),
            getDuplicateLibraryManga = mockk(relaxed = true),
            getManga = GetManga(mangas),
            setMangaCategories = mockk(relaxed = true),
            setMangaDefaultChapterFlags = setDefaultFlags,
            updateManga = UpdateManga(mangas, fetchInterval = mockk(), sourceTracker = mockk(relaxed = true)),
            autoBindOnAdd = mockk(relaxed = true),
            mergeManager = mergeManager,
            transactions = PassThroughTransactions,
            reikaiLibraryPreferences = reikaiPreferences,
            removeMangaFromLibrary = mockk(relaxed = true),
            chapterSettings = settings,
            viewerFlagSettings = viewerFlags,
        )

        override suspend fun seed(id: Long, descending: Boolean) {
            driver.seedManga(id, chapterId = id * 10)
            val direction = if (descending) Manga.CHAPTER_SORT_DESC else Manga.CHAPTER_SORT_ASC
            mangas.update(MangaUpdate(id) { chapterFlags = direction })
            mangas.update(MangaUpdate(id) { viewerFlags = if (descending) OWNER_ROTATION else SIBLING_ROTATION })
        }

        override suspend fun viewerFlagsOf(id: Long) = mangas.getMangaById(id).viewerFlags

        override suspend fun setRotation(id: Long, flag: Long) {
            mangas.setViewerFlags(id, flag, ReaderOrientation.MASK.toLong())
        }

        override suspend fun setInLibrary(id: Long, inLibrary: Boolean) {
            mangas.update(MangaUpdate(id) { favoriteAt = Instant.fromEpochMilliseconds(1L).takeIf { inLibrary } })
        }

        override suspend fun addBack(id: Long) {
            adder.confirmAddCategories(id, categoryIds = emptyList())
        }

        override suspend fun addBackIntoGroupOf(id: Long, pickedId: Long) {
            adder.addToExistingGroup(mangas.getMangaById(id), listOf(pickedId))
        }

        override suspend fun addUnderTheDefault(id: Long) {
            driver.seedManga(id, chapterId = id * 10)
            addBack(id)
        }

        // The two writes MangaViewModel.setCurrentSettingsAsDefault makes.
        override suspend fun setAsDefault(openedId: Long, applyToLibrary: Boolean) {
            libraryPreferences.setChapterSettingsDefault(mangas.getMangaById(openedId))
            if (applyToLibrary) setDefaultFlags.awaitAll()
        }

        private suspend fun shown(openedId: Long, memberIds: List<Long>) =
            settings.shown(mangas.getMangaById(openedId), memberIds)

        override suspend fun shownDescending(openedId: Long, memberIds: List<Long>) =
            shown(openedId, memberIds).sortDescending()

        override suspend fun flipSortThrough(openedId: Long, memberIds: List<Long>, beforeWrite: suspend () -> Unit) {
            settings.change(openedId, memberIds) { shown ->
                beforeWrite()
                setFlags.awaitSetSortingModeOrFlipOrder(shown, shown.sorting)
            }
        }
    }

    private inner class NovelSide : Side {
        private val novels = NovelRepositoryImpl(database)
        private val preferences = NovelPreferences(EmittingPreferenceStore())
        private val setFlags = SetNovelChapterFlags(novels, preferences)
        override val settings = NovelChapterSettings(novels)
        private val viewerFlags = NovelViewerFlagSettings(novels)
        override val mergeManager = ReikaiBindings.providesNovelMergeManager(
            groups,
            reikaiPreferences,
            propagate = { mockk(relaxed = true) },
            chapterSettings = settings,
            viewerFlagSettings = viewerFlags,
            groupCategories = NovelGroupCategories(mockk(relaxed = true), mockk(relaxed = true)),
        )
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
            viewerFlagSettings = viewerFlags,
        )

        override suspend fun setInLibrary(id: Long, inLibrary: Boolean) {
            novels.update(NovelUpdate(id) { favoriteAt = Instant.fromEpochMilliseconds(1L).takeIf { inLibrary } })
        }

        override suspend fun addBack(id: Long) {
            adder.confirmAddCategories(id, categoryIds = emptyList())
        }

        override suspend fun addBackIntoGroupOf(id: Long, pickedId: Long) {
            adder.addToExistingGroup(id, listOf(pickedId))
        }

        override suspend fun addUnderTheDefault(id: Long) {
            novels.insert(Novel.create().copy(source = "src$id", url = "/novel/$id", title = "Novel")) shouldBe id
            addBack(id)
        }

        override suspend fun setAsDefault(openedId: Long, applyToLibrary: Boolean) {
            setFlags.awaitSetAsDefault(novels.getById(openedId)!!, applyToLibrary)
        }

        override suspend fun seed(id: Long, descending: Boolean) {
            val direction = if (descending) NovelChapterFlags.SORT_DESC else NovelChapterFlags.SORT_ASC
            val stored = novels.insert(
                Novel.create().copy(
                    source = "src$id",
                    url = "/novel/$id",
                    title = "Novel",
                    chapterFlags = direction or NovelChapterFlags.SORT_LOCAL,
                    viewerFlags = if (descending) OWNER_ROTATION else SIBLING_ROTATION,
                ),
            )
            stored shouldBe id
        }

        override suspend fun viewerFlagsOf(id: Long) = novels.getById(id)!!.viewerFlags

        override suspend fun setRotation(id: Long, flag: Long) {
            novels.setViewerFlags(id, flag, ReaderOrientation.MASK.toLong())
        }

        private suspend fun shown(openedId: Long, memberIds: List<Long>) =
            settings.shown(novels.getById(openedId)!!, memberIds)

        override suspend fun shownDescending(openedId: Long, memberIds: List<Long>) =
            shown(openedId, memberIds).effectiveSortDescending(preferences)

        override suspend fun flipSortThrough(openedId: Long, memberIds: List<Long>, beforeWrite: suspend () -> Unit) {
            settings.change(openedId, memberIds) { shown ->
                beforeWrite()
                setFlags.awaitSetSortingModeOrFlipOrder(shown, shown.effectiveSorting(preferences))
            }
        }
    }

    private companion object {
        const val OWNER = 1L
        const val SIBLING = 2L
        const val OTHER = 3L
        val GROUP = listOf(OWNER, SIBLING)

        // Three rotations under ReaderOrientation.MASK, which both types keep in their reader flags.
        const val OWNER_ROTATION = 0x08L
        const val SIBLING_ROTATION = 0x10L
        const val CHANGED_ROTATION = 0x18L
    }
}
