package reikai.presentation.browse.extension.details

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnInstalledPluginMetadata
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.registry.LnRepoResult
import reikai.novel.source.NovelSource

/** What an installed plugin's details page says about its version and the repo it came from. */
class NovelPluginDetailsTest {

    private val plugin = mockk<NovelSource> {
        every { id } returns "plugin"
        every { version } returns "1.0.0"
    }
    private val installed = mapOf(SCRIPT to LnInstalledPluginMetadata(pluginId = "plugin", version = "2.0.0"))

    private fun listing(url: String) =
        LnRepoResult.Reached(listOf(LnRegistryEntry("plugin", "Plugin", "2.0.0", "", "en", url)))

    @Test
    fun `the version is the one its install recorded, as the Extensions row shows`() {
        novelPluginDetails(plugin, installed, emptyMap()).version shouldBe "2.0.0"
    }

    @Test
    fun `a plugin with no install record shows its own version`() {
        novelPluginDetails(plugin, emptyMap(), emptyMap()).version shouldBe "1.0.0"
    }

    @Test
    fun `the repo is the added one that lists its script`() {
        val repos = mapOf("https://other/index.json" to listing(OTHER), REPO to listing(SCRIPT))
        novelPluginDetails(plugin, installed, repos).repoName shouldBe "repo.example"
    }

    @Test
    fun `a repo served from its own host links to that host, not its index`() {
        val repos = mapOf(REPO to listing(SCRIPT))
        novelPluginDetails(plugin, installed, repos).repoWebsite shouldBe "https://repo.example"
    }

    @Test
    fun `a GitHub raw repo is named by its owner and links its GitHub page`() {
        val details = novelPluginDetails(plugin, installed, mapOf(GITHUB_REPO to listing(SCRIPT)))
        (details.repoName to details.repoWebsite) shouldBe
            ("LNReader" to "https://github.com/LNReader/lnreader-plugins")
    }

    @Test
    fun `a repo that did not answer names no repo`() {
        val repos = mapOf(REPO to LnRepoResult.Unreachable("offline"))
        novelPluginDetails(plugin, installed, repos).repoName shouldBe null
    }

    private companion object {
        const val REPO = "https://repo.example/plugins.min.json"
        const val GITHUB_REPO = "https://raw.githubusercontent.com/LNReader/lnreader-plugins/v3/plugins.min.json"
        const val SCRIPT = "https://repo.example/plugin.js"
        const val OTHER = "https://other/plugin.js"
    }
}
