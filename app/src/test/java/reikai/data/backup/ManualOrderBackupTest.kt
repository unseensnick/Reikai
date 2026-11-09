package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupManualOrderFields
import eu.kanade.tachiyomi.data.backup.models.BackupManualPosition
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.protobuf.ProtoBuf
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.library.ManualOrderPosition
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/** An entry's places in Manual orders, written by category order and read back by this device's ids. */
class ManualOrderBackupTest {

    private class Entry(override var manualOrder: List<BackupManualPosition> = emptyList()) : BackupManualOrderFields

    private val categories = mockk<CategoryRepository> {
        coEvery { getUnfiltered() } returns listOf(
            Category(id = 0, name = "", order = -1, flags = 0),
            Category(id = 7, name = "Reading", order = 3, flags = 0),
        )
    }

    @Test
    fun `a backup names each place's category by its order`() = runTest {
        val manualOrder = FakeLibraryManualOrderRepository().apply {
            positions.value = listOf(
                ManualOrderPosition(ContentType.NOVELS, 1, 7, 4),
                ManualOrderPosition(ContentType.NOVELS, 1, 0, 2),
            )
        }
        val entry = Entry()

        listOf(1L to entry).writeManualOrder(ContentType.NOVELS, { it }, manualOrder, categories)

        entry.manualOrder.map { it.categoryOrder to it.position } shouldBe listOf(3L to 4L, -1L to 2L)
    }

    /** Default is never among a backup's categories, so its fixed order maps straight to its id. */
    @Test
    fun `a restore maps Default by its order and others through the lookup`() {
        val entry = Entry(listOf(BackupManualPosition(-1, 2), BackupManualPosition(3, 4)))

        entry.restoredManualOrder { order -> if (order == 3L) 70L else null } shouldBe listOf(0L to 2L, 70L to 4L)
    }

    @Test
    fun `a place whose category this device lacks is dropped`() {
        val entry = Entry(listOf(BackupManualPosition(9, 1)))

        entry.restoredManualOrder { null } shouldBe emptyList()
    }

    @Test
    fun `a manga's places survive the wire`() {
        val manga = BackupManga(source = 1L, url = "/m").apply { manualOrder = listOf(BackupManualPosition(3, 4)) }

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupManga.serializer(),
            ProtoBuf.encodeToByteArray(BackupManga.serializer(), manga),
        )

        decoded.manualOrder.map { it.categoryOrder to it.position } shouldBe listOf(3L to 4L)
    }

    @Test
    fun `a novel's places survive the wire`() {
        val novel = BackupNovel(source = "src", url = "/n").apply { manualOrder = listOf(BackupManualPosition(3, 4)) }

        val decoded = ProtoBuf.decodeFromByteArray(
            BackupNovel.serializer(),
            ProtoBuf.encodeToByteArray(BackupNovel.serializer(), novel),
        )

        decoded.manualOrder.map { it.categoryOrder to it.position } shouldBe listOf(3L to 4L)
    }
}
