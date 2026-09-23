package reikai.novel.source

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginInfo

/** A plugin's WebView and Share address, which lnreader asks the plugin's own `resolveUrl` for first. */
class LnPluginSourceWebUrlTest {

    private fun source(resolved: String?) = LnPluginSource(
        mockk<LnPluginHost> { coEvery { resolveUrl("p", any(), any()) } returns resolved },
        LnPluginInfo(id = "p", name = "P", site = "https://site.example/"),
    )

    @Test
    fun `a plugin's own address for a path is the page opened`() = runTest {
        source("https://site.example/series/slug").webUrl("slug~~7", isNovel = true) shouldBe
            "https://site.example/series/slug"
    }

    @Test
    fun `a plugin without an address rule joins the path to its site`() = runTest {
        source(null).webUrl("novel/slug", isNovel = true) shouldBe "https://site.example/novel/slug"
    }
}
