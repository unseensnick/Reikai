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

    private val trusted = ApprovedRepos(plugins = setOf("https://plugins.example/index.json"))

    /** The repos the restore asked the installer to add, with Extension repos [on] or off. */
    private suspend fun addedWith(on: Boolean): List<ApprovedRepos> {
        val added = mutableListOf<ApprovedRepos>()
        val installer = mockk<RestoreExtensionInstaller>(relaxed = true) {
            coEvery { addTrusted(any()) } answers {
                added += firstArg<ApprovedRepos>()
                emptyList()
            }
        }
        restoreEncoded(
            Backup(emptyList(), backupCategories = listOf(BackupCategory("Queue"))),
            RestoreOptions(extensionStores = on, extensions = false),
            restoreExtensionInstaller = installer,
            trusted = trusted,
        )
        return added
    }

    @Test
    fun `with Extension repos off a trusted repo is not added`() = runTest {
        addedWith(on = false) shouldBe emptyList()
    }

    @Test
    fun `with Extension repos on the repos the user trusted are added`() = runTest {
        addedWith(on = true) shouldBe listOf(trusted)
    }
}
