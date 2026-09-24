package reikai.novel.source

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.source.ireader.IReaderSourceHolder
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.source.model.SourceNotInstalledException
import ireader.core.source.CatalogSource as IReaderCatalogSource

/** The registry follows the installed novel extension apps as the manga registry follows its extensions. */
class NovelSourceManagerTest {

    private val loaded = MutableStateFlow<List<Extension.Loaded>>(emptyList())
    private val seen = mockk<Preference<Map<String, LnSourceIdentity>>>(relaxed = true) {
        every { get() } returns emptyMap()
    }
    private val manager = NovelSourceManager(
        installer = { mockk<LnPluginInstaller>(relaxed = true) },
        extensionManager = mockk<ExtensionManager> { every { loadedNovelExtensionsFlow } returns loaded },
        prefs = mockk<NovelPreferences> { every { seenNovelSources() } returns seen },
    )

    @Test
    fun `an installed app's catalogue is registered`() = runTest {
        loaded.value = listOf(app(catalogue(7L)))

        manager.sources.first { it.isNotEmpty() }.map { it.id } shouldBe listOf("tachiyomi:7")
    }

    @Test
    fun `an uninstalled app's catalogue leaves the registry`() = runTest {
        loaded.value = listOf(app(catalogue(7L)))
        manager.sources.first { it.isNotEmpty() }

        loaded.value = emptyList()

        manager.sources.first { it.isEmpty() } shouldBe emptyList()
    }

    @Test
    fun `a plugin stays registered while the apps change`() = runTest {
        val plugin = mockk<NovelSource> { every { id } returns "plugin" }
        manager.register(plugin)

        loaded.value = listOf(app(catalogue(7L)))

        manager.sources.first { it.size == 2 }.map { it.id }.toSet() shouldBe setOf("plugin", "tachiyomi:7")
    }

    @Test
    fun `an app's catalogue is remembered, so its novels keep a name once it is removed`() = runTest {
        loaded.value = listOf(app(catalogue(7L)))

        val written = slot<Map<String, LnSourceIdentity>>()
        verify(timeout = 5_000) { seen.set(capture(written)) }
        written.captured["tachiyomi:7"]?.name shouldBe "App 7"
    }

    @Test
    fun `an IReader app's catalogue is registered under its own id beside a tachiyomi one's`() = runTest {
        loaded.value = listOf(app(catalogue(7L)), iReaderApp(7L))

        manager.sources.first { it.size == 2 }.map { it.id }.toSet() shouldBe setOf("tachiyomi:7", "ireader:7")
    }

    @Test
    fun `an IReader catalogue that did not change keeps its adapter when another app arrives`() = runTest {
        val iReader = iReaderApp(7L)
        loaded.value = listOf(iReader)
        val before = manager.sources.first { it.isNotEmpty() }.single()

        loaded.value = listOf(iReader, app(catalogue(8L)))

        manager.sources.first { it.size == 2 }.first { it.id == "ireader:7" } shouldBeSameInstanceAs before
    }

    /** The novel twin of manga's stub source, which throws the same exception from every call. */
    @Test
    fun `a novel source that is not installed resolves to SourceNotInstalledException`() = runTest {
        shouldThrow<SourceNotInstalledException> { manager.getOrThrow("gone") }
    }

    @Test
    fun `an installed source is named by itself`() = runTest {
        loaded.value = listOf(app(catalogue(7L)))
        manager.sources.first { it.isNotEmpty() }

        manager.nameOf("tachiyomi:7") shouldBe "App 7"
    }

    @Test
    fun `a source no longer installed is named as it was last seen`() = runTest {
        every { seen.get() } returns mapOf("gone" to LnSourceIdentity(name = "Old Name"))

        manager.nameOf("gone") shouldBe "Old Name"
    }

    @Test
    fun `a source never seen is named by its id`() = runTest {
        manager.nameOf("gone") shouldBe "gone"
    }

    private fun iReaderApp(sourceId: Long) = app().copy(
        pkgName = "ireader.app.en",
        kind = Extension.Kind.IREADER,
        sources = listOf(
            IReaderSourceHolder(
                mockk<IReaderCatalogSource> {
                    every { id } returns sourceId
                    every { name } returns "IReader $sourceId"
                    every { lang } returns "en"
                    every { getFilters() } returns emptyList()
                    every { getListings() } returns emptyList()
                },
            ),
        ),
    )

    private fun catalogue(sourceId: Long) = mockk<CatalogueSource> {
        every { id } returns sourceId
        every { name } returns "App $sourceId"
        every { lang } returns "en"
        every { supportsLatest } returns false
        every { getFilterList() } returns FilterList()
    }

    private fun app(vararg catalogues: CatalogueSource) = Extension.Loaded(
        name = "App",
        pkgName = "eu.kanade.tachiyomi.novelextension.en.app",
        versionName = "1.6.1",
        versionCode = 1,
        libVersion = 1.6,
        lang = "en",
        contentWarning = ContentWarning.SAFE,
        isShared = true,
        kind = Extension.Kind.TACHIYOMI_NOVEL,
        pkgFactory = null,
        sources = catalogues.toList(),
        icon = null,
    )
}
