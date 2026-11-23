package reikai.presentation.webview

import androidx.lifecycle.ViewModelStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginInfo
import reikai.novel.host.WebStorageSnapshot
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.util.resetVirtualDispatchers
import reikai.util.restubDefaultDispatcher
import reikai.util.virtualDispatchers

/** Which light-novel plugin, if any, keeps the storage of a page the in-app browser shows. */
class PluginWebStorageTest {

    // The model resolves its plugin on IO and stores on the app scope's IO, both on the test scheduler here.
    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    private fun plugin(webStorageUtilized: Boolean) =
        LnPluginSource(mockk(), LnPluginInfo(id = "plugin", name = "Plugin", webStorageUtilized = webStorageUtilized))

    @Test
    fun `a plugin that asks for the site's storage keeps it`() {
        webStoragePlugin(plugin(webStorageUtilized = true))?.id shouldBe "plugin"
    }

    @Test
    fun `a plugin that does not ask for the site's storage keeps nothing`() {
        webStoragePlugin(plugin(webStorageUtilized = false)) shouldBe null
    }

    @Test
    fun `a page opened for a plugin that is not installed keeps nothing`() {
        webStoragePlugin(null) shouldBe null
    }

    @Test
    fun `a page of a novel from an extension app keeps nothing`() {
        webStoragePlugin(mockk<NovelSource>()) shouldBe null
    }

    @Test
    fun `leaving the site keeps the site's storage, not the last page's`() {
        val pages = listOf(
            "https://site.example/login" to SITE_STORAGE,
            "https://accounts.other.example/o" to OTHER_STORAGE,
        )

        pluginSiteStorage("https://site.example", pages) shouldBe SITE_STORAGE
    }

    @Test
    fun `a subdomain of the site is the site`() {
        pluginSiteStorage("https://www.site.example/", listOf("https://m.site.example/x" to SITE_STORAGE)) shouldBe
            SITE_STORAGE
    }

    @Test
    fun `a host that only ends in the site's name is another site`() {
        pluginSiteStorage("https://site.example", listOf("https://evilsite.example/" to OTHER_STORAGE)) shouldBe null
    }

    @Test
    fun `a plugin with no site keeps nothing`() {
        pluginSiteStorage("", listOf("https://site.example/" to SITE_STORAGE)) shouldBe null
    }

    // The browser restored before the first plugin load pass finished, then closed at once.
    @Test
    fun `a site's storage captured before the plugin resolves is still kept when the browser closes`() = runTest {
        val loaded = CompletableDeferred<Unit>()
        val site = LnPluginSource(
            mockk(),
            LnPluginInfo(id = "plugin", name = "Plugin", site = "https://site.example", webStorageUtilized = true),
        )
        val sourceManager = mockk<NovelSourceManager>()
        coEvery { sourceManager.get("plugin") } coAnswers {
            loaded.await()
            site
        }
        val host = mockk<LnPluginHost>(relaxed = true)
        val model = PluginWebStorageViewModel("plugin", sourceManager, host, appScope())
        model.onPageStorage("https://site.example/login", SITE_STORAGE)

        ViewModelStore().apply { put("browser", model) }.clear()
        loaded.complete(Unit)
        advanceUntilIdle()

        verify { host.storeWebStorage("plugin", SITE_STORAGE) }
    }

    // A host seen again moves to the end, or the subdomain left first would keep a sign-in made after it.
    @Test
    fun `the site's host visited last keeps its storage when the browser closes`() = runTest {
        val sourceManager = mockk<NovelSourceManager>()
        coEvery { sourceManager.get("plugin") } returns LnPluginSource(
            mockk(),
            LnPluginInfo(id = "plugin", name = "Plugin", site = "https://site.example", webStorageUtilized = true),
        )
        val host = mockk<LnPluginHost>(relaxed = true)
        val model = PluginWebStorageViewModel("plugin", sourceManager, host, appScope())
        model.onPageStorage("https://www.site.example/", OTHER_STORAGE)
        model.onPageStorage("https://m.site.example/", OTHER_STORAGE)
        model.onPageStorage("https://www.site.example/login", SITE_STORAGE)

        ViewModelStore().apply { put("browser", model) }.clear()
        advanceUntilIdle()

        verify { host.storeWebStorage("plugin", SITE_STORAGE) }
    }

    // Reading the page's storage runs on Main after every page load, so a plugin that never uses it is spared.
    @Test
    fun `the browser stops reading storage once its plugin is known not to use it`() = runTest {
        val sourceManager = mockk<NovelSourceManager>()
        coEvery { sourceManager.get("plugin") } returns plugin(webStorageUtilized = false)
        val model = PluginWebStorageViewModel("plugin", sourceManager, mockk(), appScope())
        advanceUntilIdle()

        model.capturesStorage shouldBe false
    }

    // On the virtual IO, and not the test's own scope, so it outlives the model as the app scope does. Built
    // after the coEvery stubs, which leave Default answering a bare mock until restubbed.
    private fun appScope(): CoroutineScope {
        restubDefaultDispatcher()
        return CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    private companion object {
        val SITE_STORAGE = WebStorageSnapshot(local = """{"token":"site"}""", session = "{}")
        val OTHER_STORAGE = WebStorageSnapshot(local = "{}", session = "{}")
    }
}
