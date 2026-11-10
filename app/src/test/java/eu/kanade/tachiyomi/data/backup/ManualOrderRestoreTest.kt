package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.library.FakeLibraryManualOrderRepository
import reikai.domain.library.ManualOrderPosition

class ManualOrderRestoreTest {

    @Test
    fun `a restore of library entries moves the device's Manual places out of the backup's way`() = runTest {
        val manualOrder = FakeLibraryManualOrderRepository().apply {
            upsert(listOf(ManualOrderPosition(ContentType.MANGA, 1, 4, 0)))
        }

        restoreEncoded(
            Backup(emptyList(), backupCategories = listOf(BackupCategory("Queue"))),
            RestoreOptions(categories = false, appSettings = false),
            manualOrder = manualOrder,
        )

        manualOrder.positions.value.single().position shouldBe -1L
    }
}
