package reikai.data.backup

import eu.kanade.tachiyomi.data.backup.models.BackupExtensionStore
import eu.kanade.tachiyomi.data.backup.models.StringSetPreferenceValue
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.data.extension.service.ExtensionStoreService
import mihon.domain.extension.model.ContentWarning
import mihon.domain.extension.model.ExtensionStore
import mihon.domain.extension.repository.ExtensionStoreRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.novel.NovelPreferences
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRegistryFetcher
import reikai.presentation.recents.EmittingPreferenceStore

/** Where a restore installs each of the backup's missing apps and plugins from, and which repos it offers. */
class RestoreInstallsTest {

    /** One kind of thing a backup can lack, with a repo listing that installs it. */
    enum class Kind(val item: RestoreItem, val listing: RepoListings) {
        MangaApp(
            RestoreItem.App("pkg.manga", "Manga"),
            RepoListings(apps = listOf(app("pkg.manga", Extension.Kind.MANGA))),
        ),
        NovelApp(
            RestoreItem.App("pkg.novel", "Novel"),
            RepoListings(apps = listOf(app("pkg.novel", Extension.Kind.TACHIYOMI_NOVEL))),
        ),
        Plugin(
            RestoreItem.Plugin(PLUGIN_URL, "plugin.id", "Plugin"),
            RepoListings(plugins = listOf(plugin(PLUGIN_URL))),
        ),
    }

    private val backupRepo = BackupRepo("https://backup.example", "Backup", isPluginRepo = false, signingKey = KEY)

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a missing item a repo on the device lists installs after the restore`(kind: Kind) {
        planInstalls(listOf(kind.item), kind.listing, emptyList()).ready shouldBe listOf(kind.item.name)
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a missing item only a backup repo lists waits under that repo to be trusted`(kind: Kind) {
        planInstalls(
            listOf(kind.item),
            RepoListings(),
            listOf(backupRepo to kind.listing),
        ).repos.single().items shouldBe
            listOf(kind.item.name)
    }

    /** Trusting either installs it, so neither may claim it from the other. */
    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a missing item two backup repos list shows under both`(kind: Kind) {
        val other = backupRepo.copy(url = "https://other.example", name = "Other")

        planInstalls(listOf(kind.item), RepoListings(), listOf(backupRepo to kind.listing, other to kind.listing))
            .repos.map { it.items } shouldBe listOf(listOf(kind.item.name), listOf(kind.item.name))
    }

    // The Restore screen keys its repo cards by address, and a repeated key crashes the list.
    @Test
    fun `a repo the backup names twice is offered once`() {
        planInstalls(emptyList(), RepoListings(), listOf(backupRepo to RepoListings(), backupRepo to RepoListings()))
            .repos shouldBe listOf(backupRepo)
    }

    @ParameterizedTest
    @EnumSource(Kind::class)
    fun `a missing item no repo lists is named to install by hand`(kind: Kind) {
        planInstalls(listOf(kind.item), RepoListings(), emptyList()).unavailable shouldBe listOf(kind.item.name)
    }

    @Test
    fun `a backup repo listing nothing missing is still offered, since trusting it is how it comes back`() {
        planInstalls(emptyList(), RepoListings(), listOf(backupRepo to RepoListings())).repos shouldBe
            listOf(backupRepo)
    }

    @Test
    fun `a plugin another repo lists under another address matches by its id`() {
        val elsewhere = RepoListings(plugins = listOf(plugin("https://other.example/plugin.js")))

        planInstalls(listOf(Kind.Plugin.item), elsewhere, emptyList()).ready shouldBe listOf("Plugin")
    }

    // A plugin the backup never loaded is named by its script's file, which a repo listing it can name properly.
    @Test
    fun `a plugin a backup repo lists is named as the repo lists it`() {
        val unnamed = RestoreItem.Plugin(PLUGIN_URL, "plugin.id", "Plugin%5Bid%5D")

        planInstalls(listOf(unnamed), RepoListings(), listOf(backupRepo to Kind.Plugin.listing))
            .repos.single().items shouldBe listOf("Plugin")
    }

    @Test
    fun `an app two repos list installs the newer one`() {
        val listings =
            RepoListings(
                apps = listOf(app("pkg.manga", Extension.Kind.MANGA, 1), app("pkg.manga", Extension.Kind.MANGA, 3)),
            )

        listings.find(Kind.MangaApp.item as RestoreItem.App)?.versionCode shouldBe 3
    }

    // A backup can be anyone's file, so its own key is never the one a repo is offered under.
    @Test
    fun `a backup store now signing with another key is not offered and is named as unread`() = runTest {
        val plan = sources(liveKey = "other").plan(emptyList(), listOf(backupStore), emptyMap())

        plan.repos shouldBe emptyList()
        plan.unreadRepos shouldBe listOf("Backup")
    }

    @Test
    fun `a backup store still signing with its key is offered under the key read live`() = runTest {
        sources(liveKey = KEY).plan(emptyList(), listOf(backupStore), emptyMap()).repos.single().signingKey shouldBe KEY
    }

    @Test
    fun `a backup plugin repo the device already has is not offered`() = runTest {
        val novelPreferences = NovelPreferences(EmittingPreferenceStore()).apply { addedRepoUrls().set(setOf(REPO)) }
        val preferences = mapOf(novelPreferences.addedRepoUrls().key() to StringSetPreferenceValue(setOf(REPO)))

        sources(novelPreferences = novelPreferences).plan(emptyList(), emptyList(), preferences).repos shouldBe
            emptyList()
    }

    @Test
    fun `a backup plugin repo is named as the Repos screen names it, not by its address`() = runTest {
        val key = NovelPreferences(EmittingPreferenceStore()).addedRepoUrls().key()

        sources().plan(emptyList(), emptyList(), mapOf(key to StringSetPreferenceValue(setOf(REPO))))
            .repos.single().name shouldBe "plugins.example"
    }

    @Test
    fun `a backup store the device already has is not offered`() = runTest {
        sources(deviceStores = listOf(store(backupStore.indexUrl, KEY)))
            .plan(emptyList(), listOf(backupStore), emptyMap()).repos shouldBe emptyList()
    }

    private val backupStore = BackupExtensionStore(
        indexUrl = "https://backup.example/index.min.json",
        name = "Backup",
        badgeLabel = null,
        signingKey = KEY,
        contactWebsite = "https://backup.example",
        contactDiscord = null,
        isLegacy = false,
        extensionListUrl = null,
    )

    private fun sources(
        liveKey: String = KEY,
        novelPreferences: NovelPreferences = NovelPreferences(EmittingPreferenceStore()),
        deviceStores: List<ExtensionStore> = emptyList(),
    ): RestoreInstallSources {
        val live = store(backupStore.indexUrl, liveKey)
        return RestoreInstallSources(
            extensionManager = mockk<ExtensionManager>(),
            novelPreferences = novelPreferences,
            storeRepository = mockk<ExtensionStoreRepository> { coEvery { getAll() } returns deviceStores },
            storeService = mockk<ExtensionStoreService> {
                coEvery { fetch(backupStore.indexUrl) } returns Result.success(live)
                coEvery { getExtensions(live) } returns Result.success(emptyList())
            },
            registryFetcher = LnRegistryFetcher { emptyList() },
        )
    }

    private companion object {
        const val KEY = "key"
        const val REPO = "https://plugins.example/plugins.min.json"
        const val PLUGIN_URL = "https://plugins.example/plugin.js"

        fun store(indexUrl: String, key: String) = ExtensionStore(
            indexUrl = indexUrl,
            name = "Backup",
            badgeLabel = "Backup",
            signingKey = key,
            contact = ExtensionStore.Contact(website = indexUrl, discord = null),
            isLegacy = false,
            extensionListUrl = null,
        )

        fun app(pkgName: String, kind: Extension.Kind, versionCode: Long = 1) = Extension.Available(
            name = pkgName,
            pkgName = pkgName,
            versionName = "$versionCode",
            versionCode = versionCode,
            libVersion = 1.6,
            lang = "en",
            contentWarning = ContentWarning.SAFE,
            kind = kind,
            sources = emptyList(),
            apkUrl = "a.apk",
            iconUrl = "a.png",
            store = store("https://device.example/index.min.json", KEY),
        )

        fun plugin(url: String) = LnRegistryEntry(
            id = "plugin.id",
            name = "Plugin",
            version = "1",
            site = "https://site.example",
            lang = "English",
            url = url,
        )
    }
}
