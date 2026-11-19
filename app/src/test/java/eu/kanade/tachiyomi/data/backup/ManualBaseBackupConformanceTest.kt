package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.create.BackupOptions
import eu.kanade.tachiyomi.data.backup.create.creators.CategoriesBackupCreator
import eu.kanade.tachiyomi.data.backup.create.creators.NovelBackupCreator
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.models.BackupNovelCategory
import eu.kanade.tachiyomi.data.backup.restore.restorers.CategoriesRestorer
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.category.CategoryContentType
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.chapter.NoChapterNumberOverrides
import reikai.domain.chapter.NoChapterTombstoneRepository
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.NoVolumePicks
import reikai.domain.library.FakeLibraryHiddenRepository
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.novel.local.NoLocalNovelLanguages
import reikai.domain.readinglog.NoReadingLog
import reikai.domain.readinglog.NoReadingPace
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.NewCategory

/**
 * A Manual category keeps the sort it started from, which orders the series not yet placed and is
 * what Reset returns to. Backed up on one device and restored on an empty one, both content types'
 * categories come back with it, over the file format and a real database.
 */
class ManualBaseBackupConformanceTest {

    @ParameterizedTest
    @EnumSource(Type::class)
    fun `a Manual category restores with the sort it started from`(type: Type) = runTest {
        val base = 42L
        val backedUp = MangaRestoreHarness.create().use { device ->
            device.categories.insert(NewCategory(name = "Queue", flags = 0, contentType = type.contentType))
            val id = device.categories.getUnfiltered().single { it.name == "Queue" }.id
            val manualOrder = FakeLibraryManualOrderRepository().apply { setBase(id, base) }
            type.backUp(device, manualOrder)
        }

        val restored = MangaRestoreHarness.create().use { device ->
            val manualOrder = FakeLibraryManualOrderRepository()
            type.restore(device, manualOrder, backedUp)
            val id = device.categories.getUnfiltered().single { it.name == "Queue" }.id
            manualOrder.bases.value[id]
        }

        restored shouldBe base
    }

    enum class Type(val contentType: Long) {
        MANGA(CategoryContentType.MANGA) {
            override suspend fun backUp(device: MangaRestoreHarness, manualOrder: FakeLibraryManualOrderRepository) =
                ProtoBuf.encodeToByteArray(
                    ListSerializer(BackupCategory.serializer()),
                    CategoriesBackupCreator(GetCategories(device.categories), manualOrder)(),
                )

            override suspend fun restore(
                device: MangaRestoreHarness,
                manualOrder: FakeLibraryManualOrderRepository,
                backedUp: ByteArray,
            ) {
                CategoriesRestorer(
                    device.categories,
                    GetCategories(device.categories),
                    mockk(relaxed = true),
                    manualOrder,
                )(
                    ProtoBuf.decodeFromByteArray(ListSerializer(BackupCategory.serializer()), backedUp),
                )
            }
        },
        NOVEL(CategoryContentType.NOVEL) {
            override suspend fun backUp(device: MangaRestoreHarness, manualOrder: FakeLibraryManualOrderRepository) =
                ProtoBuf.encodeToByteArray(
                    ListSerializer(BackupNovelCategory.serializer()),
                    NovelBackupCreator(
                        libraryHides = FakeLibraryHiddenRepository(),
                        tombstones = NoChapterTombstoneRepository,
                        manualOrder = manualOrder,
                        novelRepository = mockk(),
                        novelChapterRepository = mockk(),
                        categoryRepository = device.categories,
                        novelTrackRepository = mockk(),
                        mergeGroupRepository = mockk(),
                        customNovelInfoRepository = mockk(),
                        novelHistoryRepository = mockk(),
                        novelSourceManager = mockk(),
                        chapterNumberOverrides = NoChapterNumberOverrides,
                        copyMarks = InMemoryChapterCopyMarks(),
                        localVolumes = NoLocalVolumes,
                        volumePicks = NoVolumePicks,
                        readingLog = NoReadingLog,
                        readingPace = NoReadingPace,
                        localNovelLanguages = NoLocalNovelLanguages,
                    ).novelCategories(BackupOptions(categories = true)),
                )

            override suspend fun restore(
                device: MangaRestoreHarness,
                manualOrder: FakeLibraryManualOrderRepository,
                backedUp: ByteArray,
            ) {
                device.novelCategoryRestorer(manualOrder).restoreCategories(
                    ProtoBuf.decodeFromByteArray(ListSerializer(BackupNovelCategory.serializer()), backedUp),
                )
            }
        },
        ;

        /** The device's categories as this type writes them into a backup file. */
        abstract suspend fun backUp(
            device: MangaRestoreHarness,
            manualOrder: FakeLibraryManualOrderRepository,
        ): ByteArray

        /** Restores [backedUp]'s categories of this type onto [device]. */
        abstract suspend fun restore(
            device: MangaRestoreHarness,
            manualOrder: FakeLibraryManualOrderRepository,
            backedUp: ByteArray,
        )
    }
}
