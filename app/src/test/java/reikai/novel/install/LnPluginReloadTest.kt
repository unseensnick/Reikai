package reikai.novel.install

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.novel.host.LnPluginInfo

/** A plugin can declare its site from a setting (a self-hosted server's address), which its load reads once. */
class LnPluginReloadTest {

    private val harness = LnPluginHarness(setOf(URL)).also {
        it.prefs.installedPluginMetadata().set(mapOf(URL to LnInstalledPluginMetadata(ID)))
    }

    @Test
    fun `reloading a plugin after its settings change gives its source the site they now declare`() = runTest {
        var site = ""
        coEvery { harness.host.inspectPlugin(any(), any(), any(), any(), any()) } answers {
            LnPluginInfo(id = ID, name = ID, site = site)
        }
        harness.installer.ensureLoaded()
        site = "https://server.test/"

        harness.installer.reload(ID)

        harness.manager.get(ID)?.site shouldBe "https://server.test/"
    }

    private companion object {
        const val ID = "selfhosted"
        const val URL = "https://repo.test/selfhosted.js"
    }
}
