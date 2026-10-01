package reikai.presentation.webview

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginInfo
import reikai.novel.host.WebStorageSnapshot
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSource

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

    private companion object {
        val SITE_STORAGE = WebStorageSnapshot(local = """{"token":"site"}""", session = "{}")
        val OTHER_STORAGE = WebStorageSnapshot(local = "{}", session = "{}")
    }
}
