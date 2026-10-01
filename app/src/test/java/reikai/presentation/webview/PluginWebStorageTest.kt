package reikai.presentation.webview

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginInfo
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
}
