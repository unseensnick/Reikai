package reikai.domain.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.tachiyomi.ui.manga.seedManga
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.core.migration.migrations.MergedGroupCategoriesMigration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.category.GetNovelCategories
import reikai.domain.db.PassThroughTransactions
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaChapterSettings
import reikai.domain.manga.MangaGroupCategories
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelGroupCategories
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.host.NovelItem
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.category.CategoryRepositoryImpl
import tachiyomi.data.manga.MangaRepositoryImpl
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEFAULT_CATEGORY_PREF_KEY
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.MangaUpdate

/**
 * A merged series sits in one set of categories: a category write through any member reaches every
 * member of its group and nothing else, and a merge leaves the group in its first member's categories.
 * One suite over both content types, each through its own [GroupCategories] and the library adder's file
 * verb its details page and add paths call, over a real database. [MEMBER] and [SIBLING] are one group,
 * [OUTSIDER] is in the library on its own.
 */
class GroupCategoriesConformanceTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    private val database = DatabaseBindings.providesDatabase(driver)

    @AfterEach
    fun tearDown() = driver.close()

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a category filed through one member files every member of its group`(type: ContentType) = runTest {
        val side = side(type).grouped()

        side.file(MEMBER, listOf(Y))

        side.categoriesOf(SIBLING) shouldBe setOf(Y)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a category filed through one member leaves an entry outside its group alone`(type: ContentType) =
        runTest {
            val side = side(type).grouped()

            side.file(MEMBER, listOf(Y))

            side.categoriesOf(OUTSIDER) shouldBe setOf(X)
        }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a library change through one member leaves every member in the same categories`(type: ContentType) =
        runTest {
            val side = side(type).grouped().apply { store(SIBLING, Z) }

            side.categories.change(SIBLING, add = listOf(Y), remove = emptyList(), side.mergeManager)

            side.categoriesOf(MEMBER) shouldBe setOf(X, Y, Z)
        }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a category a library change removes leaves every member of the group`(type: ContentType) = runTest {
        val side = side(type).grouped().apply { store(SIBLING, X, Z) }

        side.categories.change(MEMBER, add = emptyList(), remove = listOf(X), side.mergeManager)

        side.categoriesOf(SIBLING) shouldBe setOf(Z)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a library change leaves an entry outside the group alone`(type: ContentType) = runTest {
        val side = side(type).grouped()

        side.categories.change(MEMBER, add = listOf(Y), remove = listOf(X), side.mergeManager)

        side.categoriesOf(OUTSIDER) shouldBe setOf(X)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a merge gives the members joining the first member's categories`(type: ContentType) = runTest {
        val side = side(type).apply { store(SIBLING, Y) }

        side.mergeManager.merge(listOf(MEMBER, SIBLING))

        side.categoriesOf(SIBLING) shouldBe setOf(X)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `with merging switched off a category filed through one member reaches only it`(type: ContentType) =
        runTest {
            val side = side(type, mergingOn = false).grouped()

            side.file(MEMBER, listOf(Y))

            side.categoriesOf(SIBLING) shouldBe setOf(X)
        }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("reAdds")
    fun `a member added back takes its group's categories, not the default`(type: ContentType, path: AddPath) =
        runTest {
            val side = side(type).grouped().apply { setInLibrary(SIBLING, false) }

            side.addBack(SIBLING, path)

            side.categoriesOf(SIBLING) shouldBe setOf(X)
        }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("reAdds")
    fun `a member added back leaves the rest of its group where it was`(type: ContentType, path: AddPath) = runTest {
        val side = side(type).grouped().apply { setInLibrary(SIBLING, false) }

        side.addBack(SIBLING, path)

        side.categoriesOf(MEMBER) shouldBe setOf(X)
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("reAdds")
    fun `an entry added back outside any group lands in the default category`(type: ContentType, path: AddPath) =
        runTest {
            val side = side(type).grouped().apply { setInLibrary(OUTSIDER, false) }

            side.addBack(OUTSIDER, path)

            side.categoriesOf(OUTSIDER) shouldBe setOf(Y)
        }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the upgrade puts a group that disagreed in its first member's categories`(type: ContentType) = runTest {
        val side = side(type).grouped().apply { store(SIBLING, Z) }

        side.upgrade()

        side.categoriesOf(SIBLING) shouldBe setOf(X)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `the upgrade leaves an entry outside any group alone`(type: ContentType) = runTest {
        val side = side(type).grouped().apply { store(OUTSIDER, Z) }

        side.upgrade()

        side.categoriesOf(OUTSIDER) shouldBe setOf(Z)
    }

    /** [MEMBER], [SIBLING] and [OUTSIDER] in the library, each filed under [X], in no group yet. */
    private suspend fun side(type: ContentType, mergingOn: Boolean = true): Side {
        Database.Schema.create(driver).await()
        listOf(X, Y, Z).forEach { id ->
            driver.execute(
                null,
                "INSERT INTO category(id, name, `order`, flags, content_type) VALUES ($id, 'c$id', $id, 0, 0)",
                0,
            ).await()
        }
        val preferences = ReikaiLibraryPreferences(
            InMemoryPreferenceStore(sequenceOf(InMemoryPreference("series_merging_enabled", mergingOn, true))),
        )
        val side = if (type == ContentType.MANGA) MangaSide(preferences) else NovelSide(preferences)
        listOf(MEMBER, SIBLING, OUTSIDER).forEach {
            side.seed(it)
            side.store(it, X)
        }
        return side
    }

    private suspend fun Side.grouped() = apply { groups.merge(type, listOf(MEMBER, SIBLING)) }

    private abstract inner class Side(val type: ContentType) {
        abstract val categories: GroupCategories
        abstract val mergeManager: EntryMergeManager
        abstract suspend fun seed(id: Long)

        /** Writes [ids] onto [id] alone, the way a restore or an older build left it. */
        abstract suspend fun store(id: Long, vararg ids: Long)

        abstract suspend fun categoriesOf(id: Long): Set<Long>

        /** Files [id] under [ids] through the adder's file verb, as the details picker and every add do. */
        abstract suspend fun file(id: Long, ids: List<Long>)

        abstract suspend fun setInLibrary(id: Long, inLibrary: Boolean)

        /** Adds [id] back through [path], with [Y] set as the default category. */
        abstract suspend fun addBack(id: Long, path: AddPath)

        /** Runs the upgrade migration that aligns every group's categories. */
        abstract suspend fun upgrade()
    }

    /** The add paths a removed entry can come back through, each a different entry point. */
    enum class AddPath { BROWSE, STORED, UNASKED, BULK }

    private val groups = MergeGroupRepositoryImpl(database)
    private val categoryRepository = CategoryRepositoryImpl(database)

    private inner class MangaSide(reikaiPreferences: ReikaiLibraryPreferences) : Side(ContentType.MANGA) {
        private val mangas = MangaRepositoryImpl(database)
        private val getCategories = GetCategories(categoryRepository)
        private val setMangaCategories = SetMangaCategories(mangas)
        override val categories = MangaGroupCategories(getCategories, setMangaCategories)
        override val mergeManager = MangaMergeManager(groups, reikaiPreferences, categories::adoptOwnerCategories) {}
        private val adder = MangaLibraryAdder(
            sourceManager = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(
                InMemoryPreferenceStore(sequenceOf(InMemoryPreference(DEFAULT_CATEGORY_PREF_KEY, Y.toInt(), -1))),
            ),
            getCategories = getCategories,
            getDuplicateLibraryManga = mockk(relaxed = true),
            getManga = GetManga(mangas),
            setMangaCategories = setMangaCategories,
            setMangaDefaultChapterFlags = mockk(relaxed = true),
            updateManga = UpdateManga(mangas, fetchInterval = mockk(), sourceTracker = mockk(relaxed = true)),
            autoBindOnAdd = mockk(relaxed = true),
            mergeManager = mergeManager,
            transactions = PassThroughTransactions,
            reikaiLibraryPreferences = reikaiPreferences,
            removeMangaFromLibrary = mockk(relaxed = true),
            chapterSettings = MangaChapterSettings(mangas),
        )

        override suspend fun seed(id: Long) = driver.seedManga(id, chapterId = id * 10)

        override suspend fun store(id: Long, vararg ids: Long) = setMangaCategories.await(id, ids.toList())

        override suspend fun categoriesOf(id: Long) = getCategories.await(id).map { it.id }.toSet()

        override suspend fun file(id: Long, ids: List<Long>) = adder.moveToCategories(mangas.getMangaById(id), ids)

        override suspend fun setInLibrary(id: Long, inLibrary: Boolean) {
            mangas.update(MangaUpdate(id) { favoriteAt = 1L.takeIf { inLibrary } })
        }

        override suspend fun addBack(id: Long, path: AddPath) {
            when (path) {
                // Manga's stored-row add is the browse add: both resolve through resolveAddFavorite's rule.
                AddPath.BROWSE, AddPath.STORED -> adder.resolveAddFavorite(mangas.getMangaById(id))
                AddPath.UNASKED -> adder.addWithoutAsking(id)
                AddPath.BULK -> {
                    adder.favoriteFromBrowse(id)
                    adder.fileAdded(id, listOf(Y))
                }
            }
        }

        override suspend fun upgrade() {
            MergedGroupCategoriesMigration(
                AlignGroupCategories(mergeManager, categories, mockk(relaxed = true), mockk(relaxed = true)),
            ).invoke(mockk(relaxed = true))
        }
    }

    private inner class NovelSide(reikaiPreferences: ReikaiLibraryPreferences) : Side(ContentType.NOVELS) {
        private val novels = NovelRepositoryImpl(database)
        private val getNovelCategories = GetNovelCategories(categoryRepository)
        private val setNovelCategories = SetNovelCategories(novels)
        override val categories = NovelGroupCategories(getNovelCategories, setNovelCategories)
        override val mergeManager = NovelMergeManager(groups, reikaiPreferences, categories::adoptOwnerCategories) {}
        private val adder = NovelLibraryAdder(
            novelRepository = novels,
            manager = mockk(relaxed = true),
            getNovelCategories = getNovelCategories,
            setNovelCategories = setNovelCategories,
            updateNovel = UpdateNovel(novels, sourceTracker = mockk(relaxed = true)),
            novelPreferences = NovelPreferences(
                InMemoryPreferenceStore(sequenceOf(InMemoryPreference("default_novel_category", Y.toInt(), -1))),
            ),
            mergeManager = mergeManager,
            transactions = PassThroughTransactions,
            reikaiLibraryPreferences = reikaiPreferences,
            autoBindOnAdd = mockk(relaxed = true),
            removeNovelsFromLibrary = mockk(relaxed = true),
            chapterSettings = NovelChapterSettings(novels),
        )

        override suspend fun seed(id: Long) {
            val stored = novels.insert(Novel.create().copy(source = "src$id", url = "/novel/$id", title = "Novel"))
            stored shouldBe id
            novels.update(NovelUpdate(id) { favoriteAt = 1L })
        }

        override suspend fun store(id: Long, vararg ids: Long) = setNovelCategories.await(id, ids.toList())

        override suspend fun categoriesOf(id: Long) = getNovelCategories.awaitByNovelId(id).map { it.id }.toSet()

        override suspend fun file(id: Long, ids: List<Long>) = adder.applyCategories(id, ids)

        override suspend fun setInLibrary(id: Long, inLibrary: Boolean) {
            novels.update(NovelUpdate(id) { favoriteAt = 1L.takeIf { inLibrary } })
        }

        override suspend fun addBack(id: Long, path: AddPath) {
            when (path) {
                AddPath.BROWSE -> adder.addToLibrary(
                    NovelItem(name = "Novel", path = "/novel/$id", cover = null),
                    "src$id",
                )
                AddPath.STORED -> adder.addStoredToLibrary(id)
                // Novels have no add that skips the picker outright; the bulk add is the unasked one.
                AddPath.UNASKED, AddPath.BULK -> {
                    adder.favoriteForAdd(id)
                    adder.fileAdded(id, listOf(Y))
                }
            }
        }

        override suspend fun upgrade() {
            MergedGroupCategoriesMigration(
                AlignGroupCategories(mockk(relaxed = true), mockk(relaxed = true), mergeManager, categories),
            ).invoke(mockk(relaxed = true))
        }
    }

    companion object {
        @JvmStatic
        fun reAdds() = listOf(ContentType.MANGA, ContentType.NOVELS).flatMap { type ->
            AddPath.entries.map { Arguments.of(type, it) }
        }

        const val MEMBER = 1L
        const val SIBLING = 2L
        const val OUTSIDER = 3L
        const val X = 10L
        const val Y = 11L
        const val Z = 12L
    }
}
