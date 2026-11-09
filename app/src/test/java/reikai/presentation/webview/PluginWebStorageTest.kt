package reikai.presentation.webview

import androidx.lifecycle.ViewModelStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginInfo
import reikai.novel.host.WebStorageSnapshot
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.util.testAppScope

/** Which light-novel plugin, if any, keeps the storage of a page the in-app browser shows. */
class PluginWebStorageTest {

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
    fun `a site's storage captured before the plugin resolves is still kept when the browser closes`() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        try {
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
            val model = PluginWebStorageViewModel("plugin", null, sourceManager, host, testAppScope())
            model.onPageStorage("https://site.example/login", SITE_STORAGE)

            ViewModelStore().apply { put("browser", model) }.clear()
            loaded.complete(Unit)

            verify(timeout = 5_000) { host.storeWebStorage("plugin", SITE_STORAGE) }
        } finally {
            Dispatchers.resetMain()
        }
    }

    private companion object {
        val SITE_STORAGE = WebStorageSnapshot(local = """{"token":"site"}""", session = "{}")
        val OTHER_STORAGE = WebStorageSnapshot(local = "{}", session = "{}")
    }
}
