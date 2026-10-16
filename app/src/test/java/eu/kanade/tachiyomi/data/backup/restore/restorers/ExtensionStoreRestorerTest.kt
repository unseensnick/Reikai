package eu.kanade.tachiyomi.data.backup.restore.restorers

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.jupiter.api.Test

class ExtensionStoreRestorerTest {

    private val stored = mutableListOf<ExtensionStore>()
    private val repository = mockk<ExtensionStoreRepository> {
        coEvery { getAll() } answers { stored.toList() }
        coEvery { upsert(any()) } answers {
            val store = firstArg<ExtensionStore>()
            stored.removeAll { it.indexUrl == store.indexUrl }
            stored += store
        }
    }

    private fun backup(key: String) = BackupExtensionStore(
        indexUrl = "https://repo.example/index.min.json",
        name = "Repo",
        badgeLabel = null,
        signingKey = key,
        contactWebsite = "https://repo.example",
        contactDiscord = null,
        isLegacy = true,
        extensionListUrl = null,
    )

    @Test
    fun `a store already on the device keeps its own signing key`() = runTest {
        stored += ExtensionStore(
            indexUrl = backup("device").indexUrl,
            name = "Repo",
            badgeLabel = "Repo",
            signingKey = "device",
            contact = ExtensionStore.Contact(website = "https://repo.example", discord = null),
            isLegacy = true,
            extensionListUrl = null,
        )

        ExtensionStoreRestorer(repository)(backup("from-backup"))

        stored.single().signingKey shouldBe "device"
    }

    // A backup can be anyone's file: a store it brings is added only once the user trusts it, read live.
    @Test
    fun `a store the device lacks is not written from the backup`() = runTest {
        ExtensionStoreRestorer(repository)(backup("from-backup"))

        stored.shouldBeEmpty()
    }
}
