package reikai.presentation.webview

import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.novel.source.LnPluginSource
import reikai.novel.source.NovelSource

/** Which light-novel plugin, if any, keeps the storage of a page the in-app browser shows. */
class PluginWebStorageTest {

    @Test
    fun `a page opened for a plugin keeps its storage for that plugin, installed or not`() {
        webStoragePluginId("plugin", null, null) shouldBe "plugin"
    }

    @Test
    fun `a page of a novel from a plugin keeps its storage for that plugin`() {
        webStoragePluginId(null, "plugin", mockk<LnPluginSource>()) shouldBe "plugin"
    }

    @Test
    fun `a page of a novel from an extension app keeps nothing`() {
        webStoragePluginId(null, "1234", mockk<NovelSource>()) shouldBe null
    }
}
