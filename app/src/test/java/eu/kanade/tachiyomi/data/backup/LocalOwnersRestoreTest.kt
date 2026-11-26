package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupManga
import eu.kanade.tachiyomi.data.backup.models.BackupNovel
import eu.kanade.tachiyomi.data.backup.models.BackupVolumeOwner
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.data.local.CombineLocalVolumes
import reikai.domain.library.StoredType
import reikai.domain.local.VolumeOwner

/** The volumes a local series lists from other folders come back as a combine applies them, once every series is. */
class LocalOwnersRestoreTest {

    @Test
    fun `only local series and their owner rows reach the combine step, for both content types`() = runTest {
        val combine = mockk<CombineLocalVolumes>(relaxed = true)
        val backup = Backup(
            backupManga = listOf(
                // A remote series, which no local grouping can claim, even one a crafted backup lends a folder.
                BackupManga(
                    source = 1L,
                    url = "Remote",
                    volumeOwners = listOf(BackupVolumeOwner("Kept", VolumeOwner.WHOLE)),
                ),
                BackupManga(
                    source = 0L,
                    url = "Kept",
                    volumeOwners = listOf(BackupVolumeOwner("Other", VolumeOwner.WHOLE)),
                ),
            ),
            backupNovels = listOf(
                BackupNovel(
                    source = "s",
                    url = "Remote",
                    volumeOwners = listOf(BackupVolumeOwner("Saga", VolumeOwner.WHOLE)),
                ),
                BackupNovel(
                    source = "local",
                    url = "Saga",
                    volumeOwners = listOf(BackupVolumeOwner("Side", VolumeOwner.WHOLE)),
                ),
            ),
        )

        restoreEncoded(
            backup,
            RestoreOptions(libraryEntries = true, categories = false, appSettings = false),
            combineLocalVolumes = combine,
        )

        coVerify {
            combine.restoreOwners(
                StoredType.MANGA,
                listOf(VolumeOwner("Other", VolumeOwner.WHOLE, "Kept")),
                listOf("Kept"),
            )
            combine.restoreOwners(
                StoredType.NOVELS,
                listOf(VolumeOwner("Side", VolumeOwner.WHOLE, "Saga")),
                listOf("Saga"),
            )
        }
    }
}
