package eu.kanade.tachiyomi.data.backup

import eu.kanade.tachiyomi.data.backup.models.Backup
import eu.kanade.tachiyomi.data.backup.models.BackupCategory
import eu.kanade.tachiyomi.data.backup.restore.RestoreOptions
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.data.backup.ApprovedRepos
import reikai.data.backup.RestoreExtensionInstaller

class RestoreExtensionReposTest {

    @Test
    fun `with Extension repos off a trusted repo is not added`() = runTest {
        val added = mutableListOf<ApprovedRepos>()
        val installer = mockk<RestoreExtensionInstaller>(relaxed = true) {
            coEvery { addTrusted(any()) } answers {
                added += firstArg<ApprovedRepos>()
                emptyList()
            }
        }

        restoreEncoded(
            Backup(emptyList(), backupCategories = listOf(BackupCategory("Queue"))),
            RestoreOptions(extensionStores = false, extensions = false),
            restoreExtensionInstaller = installer,
            trusted = ApprovedRepos(plugins = setOf("https://plugins.example/index.json")),
        )

        added shouldBe emptyList()
    }
}
