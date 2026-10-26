package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.MangaRestorer
import eu.kanade.tachiyomi.data.backup.restore.restorers.NovelRestorer
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.category.CategoryContentType
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.db.PassThroughTransactions
import reikai.domain.merge.RestoreMergeGroups
import reikai.domain.novel.NovelRepository
import reikai.domain.readinglog.NoReadingLog
import tachiyomi.domain.backup.model.RestoredManga
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.model.NewCategory
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * Backups store an entry's categories as the *order* indices of the source device's categories, never
 * names or ids (ids differ per device). On restore each order maps through the backup's own category list
 * to a name, then to that name's id on this device; a name absent here is dropped, not mis-assigned. The
 * category list itself reuses a row the device already has by name. Both rules run each type's restorer.
 */
class RestoreCategoryMatchConformanceTest {

    // Backup-side categories: same names, different orders/ids, plus one ("Ghost") absent locally.
    private val backupCategories = listOf("Reading" to 5L, "Completed" to 9L, "Ghost" to 3L)

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `category orders map through backup names to this device's category ids`(type: Type) = runTest {
        type.restoredCategoryIds(type.device(), backupCategories, listOf(5, 9)) shouldContainExactly listOf(100, 200)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a category whose name is absent locally is dropped`(type: Type) = runTest {
        type.restoredCategoryIds(type.device(), backupCategories, listOf(5, 3, 9)) shouldContainExactly
            listOf(100, 200)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `an entry binds to its own type's category when a universal one shares its name`(type: Type) = runTest {
        val sameName = listOf(
            Category(id = 100, name = "Reading", order = 0, flags = 0, contentType = type.contentType),
            Category(id = 300, name = "Reading", order = 1, flags = 0, contentType = CategoryContentType.UNIVERSAL),
        )

        type.restoredCategoryIds(sameName, backupCategories, listOf(5)) shouldContainExactly listOf(100)
    }

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a category already on the device is reused rather than created again`(type: Type) = runTest {
        type.insertedCategories(type.device(), listOf("Reading", "Fantasy")) shouldBe listOf("Fantasy")
    }

    enum class Type(val contentType: Long) {
        MANGA(CategoryContentType.MANGA) {
            override suspend fun restoredCategoryIds(
                device: List<Category>,
                backup: List<Pair<String, Long>>,
                orders: List<Long>,
            ): List<Long> {
                val entries = mutableListOf<RestoredManga>()
                MangaRestorer(
                    restoreRepository = mockk {
                        coEvery { restoreManga(any(), any()) } answers { entries += firstArg<List<RestoredManga>>() }
                    },
                    getCategories = mockk { coEvery { await() } returns device },
                    fetchInterval = mockk(relaxed = true),
                    getMangaByUrlAndSourceId = mockk(),
                    restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
                    setCustomMangaInfo = mockk(relaxed = true),
                ).restore(
                    listOf(BackupManga(source = 1L, url = "u", title = "T", categories = orders)),
                    backup.map { (name, order) -> BackupCategory(name = name, order = order) },
                )
                return entries.single().categoryIds
            }

            override suspend fun insertedCategories(device: List<Category>, backup: List<String>): List<String> {
                val repository = categoryRepository(device)
                CategoriesRestorer(repository.first, GetCategories(repository.first), mockk(relaxed = true))(
                    backup.map { BackupCategory(name = it) },
                )
                return repository.second.captured.map { it.name }
            }
        },
        NOVEL(CategoryContentType.NOVEL) {
            override suspend fun restoredCategoryIds(
                device: List<Category>,
                backup: List<Pair<String, Long>>,
                orders: List<Long>,
            ): List<Long> {
                val written = slot<List<Long>>()
                val novels = mockk<NovelRepository>(relaxed = true) {
                    coEvery { getByUrlAndSource(any(), any()) } returns null
                    coEvery { insert(any()) } returns 1L
                    coEvery { getById(any()) } returns null
                    coEvery { setCategories(1L, capture(written)) } just Runs
                }
                novelRestorer(novels, categoryRepository(device).first).restore(
                    BackupNovel(source = "src", url = "u", title = "T", categories = orders),
                    backup.map { (name, order) -> BackupNovelCategory(name = name, order = order) },
                )
                return written.captured
            }

            override suspend fun insertedCategories(device: List<Category>, backup: List<String>): List<String> {
                val repository = categoryRepository(device)
                novelRestorer(mockk(relaxed = true), repository.first)
                    .restoreCategories(backup.map { BackupNovelCategory(name = it) })
                return repository.second.captured.map { it.name }
            }
        },
        ;

        /** Two categories of this type on the device, at ids 100 and 200. */
        fun device() = listOf(
            Category(id = 100, name = "Reading", order = 0, flags = 0, contentType = contentType),
            Category(id = 200, name = "Completed", order = 1, flags = 0, contentType = contentType),
        )

        /** The ids a restored entry with [orders] is filed under, against [backup]'s (name, order) list. */
        abstract suspend fun restoredCategoryIds(
            device: List<Category>,
            backup: List<Pair<String, Long>>,
            orders: List<Long>,
        ): List<Long>

        /** The names a restore of [backup]'s category list inserts over [device]. */
        abstract suspend fun insertedCategories(device: List<Category>, backup: List<String>): List<String>
    }

    private companion object {
        /** Each library's read sees its own rows plus the universal ones, as category.sq filters them. */
        fun categoryRepository(device: List<Category>): Pair<CategoryRepository, CapturingSlot<List<NewCategory>>> {
            val inserted = slot<List<NewCategory>>()
            val repository = mockk<CategoryRepository> {
                coEvery { getAll(CategoryContentType.MANGA) } returns
                    device.filter { it.contentType != CategoryContentType.NOVEL }
                coEvery { getAll(CategoryContentType.NOVEL) } returns
                    device.filter { it.contentType != CategoryContentType.MANGA }
                coEvery { insertAll(capture(inserted)) } just Runs
            }
            return repository to inserted
        }

        fun novelRestorer(novels: NovelRepository, categories: CategoryRepository) = NovelRestorer(
            novelRepository = novels,
            novelChapterRepository = mockk { coEvery { getByNovelId(any()) } returns emptyList() },
            categoryRepository = categories,
            novelTrackRepository = mockk(relaxed = true),
            restoreMergeGroups = RestoreMergeGroups(mockk(relaxed = true), PassThroughTransactions),
            setCustomNovelInfo = mockk(relaxed = true),
            novelHistoryRepository = mockk(relaxed = true),
            chapterNumberOverrides = NoChapterNumberOverrides,
            readingLog = NoReadingLog,
        )
    }
}
