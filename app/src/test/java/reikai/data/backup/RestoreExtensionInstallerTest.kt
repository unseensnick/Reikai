package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupExtension
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.jupiter.api.Test
import reikai.novel.install.LnPluginInstaller
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRepoRegistries
import java.io.IOException

/** After a restore: the repos the user trusted, and the backup's apps that did not come back. */
class RestoreExtensionInstallerTest {

    private val stored = mutableListOf<ExtensionStore>()
    private val storeRepository = mockk<ExtensionStoreRepository> {
        coEvery { upsert(any()) } answers { stored += firstArg<ExtensionStore>() }
    }

    private val addedPluginRepos = mutableListOf<String>()

    private fun installer(
        liveKey: String?,
        sources: RestoreInstallSources = mockk(),
        pluginInstaller: LnPluginInstaller = mockk(),
        pluginRepoReachable: Boolean = true,
        extensionManager: ExtensionManager = mockk(),
    ) = RestoreExtensionInstaller(
        sources = sources,
        storeService = mockk<ExtensionStoreService> {
            coEvery { fetch(URL) } returns
                (liveKey?.let { Result.success(store(it)) } ?: Result.failure(IOException("unreachable")))
        },
        storeRepository = storeRepository,
        pluginRepos = mockk<LnRepoRegistries> {
            coEvery { add(any()) } answers {
                if (pluginRepoReachable) {
                    addedPluginRepos += firstArg<String>()
                    Result.success(Unit)
                } else {
                    Result.failure(IOException("unreachable"))
                }
            }
        },
        extensionManager = extensionManager,
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
    fun `a trusted repo whose key changed is reported`() = runTest {
        installer(liveKey = "other").addTrusted(ApprovedRepos(apps = mapOf(URL to KEY))) shouldBe
            listOf("Repo not added (signing key changed): $URL")
    }

    @Test
    fun `a trusted repo that cannot be reached is reported`() = runTest {
        installer(liveKey = null).addTrusted(ApprovedRepos(apps = mapOf(URL to KEY))) shouldBe
            listOf("Repo not added (unreachable): $URL")
    }

    @Test
    fun `a trusted plugin repo is added`() = runTest {
        installer(liveKey = KEY).addTrusted(ApprovedRepos(plugins = setOf(PLUGIN_REPO)))

        addedPluginRepos shouldBe listOf(PLUGIN_REPO)
    }

    @Test
    fun `a trusted plugin repo that cannot be reached is reported`() = runTest {
        installer(
            liveKey = KEY,
            pluginRepoReachable = false,
        ).addTrusted(ApprovedRepos(plugins = setOf(PLUGIN_REPO))) shouldBe
            listOf("Repo not added (unreachable): $PLUGIN_REPO")
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

    @Test
    fun `a missing app that installs is not reported`() = runTest {
        installListed(flowOf(InstallStep.Pending, InstallStep.Installing, InstallStep.Installed)).shouldBeEmpty()
    }

    // The installer reports a dismissed prompt as Idle, which is not a failure the user needs to retry.
    @Test
    fun `a missing app whose install prompt was dismissed is reported as cancelled`() = runTest {
        installListed(flowOf(InstallStep.Pending, InstallStep.Idle)) shouldBe
            listOf(RestoreExtensionInstaller.NotInstalled("Foo", RestoreExtensionInstaller.Reason.InstallCancelled))
    }

    /** Restores the backup's one missing app, which a trusted repo lists, through an install that emits [steps]. */
    private suspend fun installListed(steps: Flow<InstallStep>): List<RestoreExtensionInstaller.NotInstalled> {
        val listing = Extension.Available(
            name = "Foo",
            pkgName = "pkg.foo",
            versionName = "1",
            versionCode = 1,
            libVersion = 1.6,
            lang = "en",
            contentWarning = ContentWarning.SAFE,
            kind = Extension.Kind.MANGA,
            sources = emptyList(),
            apkUrl = "a.apk",
            iconUrl = "a.png",
            store = store(KEY),
        )
        val sources = mockk<RestoreInstallSources> {
            coEvery { missing(any(), any()) } returns listOf(RestoreItem.App("pkg.foo", "Foo"))
            coEvery { device() } returns RepoListings(apps = listOf(listing))
        }
        return installer(
            liveKey = KEY,
            sources,
            extensionManager = mockk { every { installExtension(listing) } returns steps },
        ).install(listOf(BackupExtension("pkg.foo", "Foo")), emptyMap())
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
        const val PLUGIN_REPO = "https://plugins.example/plugins.min.json"
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
