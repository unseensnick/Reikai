package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.jupiter.api.Test
import reikai.novel.install.LnPluginInstaller
import reikai.novel.registry.LnRegistryEntry

/** After a restore: the repos the user trusted, and the backup's apps that did not come back. */
class RestoreExtensionInstallerTest {

    private val stored = mutableListOf<ExtensionStore>()
    private val storeRepository = mockk<ExtensionStoreRepository> {
        coEvery { upsert(any()) } answers { stored += firstArg<ExtensionStore>() }
    }

    private fun installer(
        liveKey: String,
        sources: RestoreInstallSources = mockk(),
        pluginInstaller: LnPluginInstaller = mockk(),
    ) = RestoreExtensionInstaller(
        sources = sources,
        storeService = mockk<ExtensionStoreService> {
            coEvery { fetch(URL) } returns Result.success(store(liveKey))
        },
        storeRepository = storeRepository,
        pluginRepos = mockk(),
        extensionManager = mockk(),
        pluginInstaller = pluginInstaller,
    )

    @Test
    fun `a trusted repo still signing with the key the user saw is added`() = runTest {
        installer(liveKey = KEY).addTrusted(ApprovedRepos(apps = mapOf(URL to KEY)))

        stored.single().signingKey shouldBe KEY
    }

    @Test
    fun `a trusted repo that changed its key since the Restore screen is not added`() = runTest {
        installer(liveKey = "other").addTrusted(ApprovedRepos(apps = mapOf(URL to KEY)))

        stored.shouldBeEmpty()
    }

    @Test
    fun `a missing app no repo lists is reported, not counted as installed`() = runTest {
        val app = RestoreItem.App("pkg.foo", "Foo")
        val sources = mockk<RestoreInstallSources> {
            coEvery { missing(any(), any()) } returns listOf(app)
            coEvery { device() } returns RepoListings()
        }

        installer(liveKey = KEY, sources).install(listOf(BackupExtension("pkg.foo", "Foo")), emptyMap()) shouldBe
            listOf(RestoreExtensionInstaller.NotInstalled("Foo", RestoreExtensionInstaller.Reason.NotListed))
    }

    // A plugin the backup never loaded is named by its script's file; the Restore screen named it by its listing.
    @Test
    fun `a listed plugin that fails to install is reported under its listing's name`() = runTest {
        val plugin = RestoreItem.Plugin(PLUGIN_URL, null, "plugin")
        val sources = mockk<RestoreInstallSources> {
            coEvery { missing(any(), any()) } returns listOf(plugin)
            coEvery { device() } returns RepoListings(
                plugins = listOf(
                    LnRegistryEntry(
                        id = "p",
                        name = "Royal Road",
                        version = "1",
                        site = "s",
                        lang = "English",
                        url = PLUGIN_URL,
                    ),
                ),
            )
        }
        val pluginInstaller = mockk<LnPluginInstaller> {
            coEvery { installFromUrl(any(), any()) } throws IllegalStateException()
        }

        installer(liveKey = KEY, sources, pluginInstaller).install(emptyList(), emptyMap()) shouldBe
            listOf(RestoreExtensionInstaller.NotInstalled("Royal Road", RestoreExtensionInstaller.Reason.InstallFailed))
    }

    private companion object {
        const val PLUGIN_URL = "https://plugins.example/plugin.js"
        const val URL = "https://repo.example/index.min.json"
        const val KEY = "key"

        fun store(key: String) = ExtensionStore(
            indexUrl = URL,
            name = "Repo",
            badgeLabel = "Repo",
            signingKey = key,
            contact = ExtensionStore.Contact(website = URL, discord = null),
            isLegacy = false,
            extensionListUrl = null,
        )
    }
}
