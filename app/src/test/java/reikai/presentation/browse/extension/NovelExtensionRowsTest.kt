package reikai.presentation.browse.extension

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.jupiter.api.Test
import reikai.novel.install.LnPluginLoadFailure
import reikai.novel.registry.LnRegistryEntry
import reikai.novel.source.NovelSource
import reikai.novel.update.LnPluginUpdate

/**
 * The novel half feeds the shared Extensions list from three lists that are built independently,
 * where manga's arrive already partitioned. These pin the rules that closes that gap.
 */
class NovelExtensionRowsTest {

    @Test
    fun `a plugin with an update pending is only under Updates`() {
        val rows = novelExtensionRows(
            updates = listOf(update("novelbin")),
            notLoaded = emptyList(),
            installed = listOf(source("novelbin"), source("royalroad")),
            available = emptyList(),
        )

        rows.sectionsById() shouldContainExactly listOf(
            "novelbin" to ExtensionSection.Updates,
            "royalroad" to ExtensionSection.Installed,
        )
    }

    @Test
    fun `a plugin that failed to load is under Not loaded`() {
        val rows = novelExtensionRows(
            updates = emptyList(),
            notLoaded = listOf(failure("novelbin")),
            installed = listOf(source("royalroad")),
            available = listOf(entry("novelbin")),
        )

        rows.sectionsById() shouldContainExactly listOf(
            "novelbin" to ExtensionSection.NotLoaded,
            "royalroad" to ExtensionSection.Installed,
        )
    }

    @Test
    fun `a plugin with an update pending names the version it brings`() {
        val rows = novelExtensionRows(
            updates = listOf(update("novelbin")),
            notLoaded = emptyList(),
            installed = listOf(source("novelbin")),
            available = emptyList(),
        )

        rows.single().updateVersion shouldBe "2.0.0"
    }

    /** Updating reinstalls the plugin, which is the likeliest fix for one that did not load. */
    @Test
    fun `a plugin that failed to load with an update pending is only under Updates`() {
        val rows = novelExtensionRows(
            updates = listOf(update("novelbin")),
            notLoaded = listOf(failure("novelbin")),
            installed = emptyList(),
            available = emptyList(),
        )

        rows.sectionsById() shouldContainExactly listOf("novelbin" to ExtensionSection.Updates)
    }

    /** Its tap and menu then open why it did not load, since its details page has no source to show. */
    @Test
    fun `a plugin that failed to load with an update pending carries the failure to its Updates row`() {
        val rows = novelExtensionRows(
            updates = listOf(update("novelbin")),
            notLoaded = listOf(failure("novelbin")),
            installed = emptyList(),
            available = emptyList(),
        )

        rows.single().payload shouldBe NovelPluginUpdateRow(update("novelbin"), failure("novelbin"))
    }

    @Test
    fun `a loaded plugin with an update pending carries no failure`() {
        val rows = novelExtensionRows(
            updates = listOf(update("novelbin")),
            notLoaded = listOf(failure("royalroad")),
            installed = listOf(source("novelbin")),
            available = emptyList(),
        )

        rows.first().payload shouldBe NovelPluginUpdateRow(update("novelbin"), failure = null)
    }

    @Test
    fun `Update all updates a plugin that failed to load too`() {
        val model = mockk<LnPluginManagerViewModel>(relaxUnitFun = true) {
            every { state } returns MutableStateFlow(LnPluginManagerViewModel.State())
        }
        val rows = novelExtensionRows(listOf(update("novelbin")), listOf(failure("novelbin")), emptyList(), emptyList())

        NovelExtensionsProvider(model).updateAll(rows)

        verify { model.update(update("novelbin")) }
    }

    @Test
    fun `an installed plugin still offered by a repo is not listed again as available`() {
        // The model matches those two lists by URL, so a plugin reachable at a second URL is in both.
        val rows = novelExtensionRows(
            updates = emptyList(),
            notLoaded = emptyList(),
            installed = listOf(source("archiveofourown")),
            available = listOf(entry("archiveofourown")),
        )

        rows.sectionsById() shouldContainExactly listOf("archiveofourown" to ExtensionSection.Installed)
    }

    @Test
    fun `available plugins split by language, normalised to a code`() {
        // The registry names the language in the language itself; a manga extension gives the code.
        val rows = novelExtensionRows(
            updates = emptyList(),
            notLoaded = emptyList(),
            installed = emptyList(),
            available = listOf(entry("uno", lang = "Español"), entry("dos", lang = "es")),
        )

        rows.map { it.section }.distinct() shouldBe listOf(ExtensionSection.Available("es"))
    }

    @Test
    fun `a row carries its site and id to the search box`() {
        val rows = novelExtensionRows(emptyList(), emptyList(), listOf(source("novelbin")), emptyList())

        rows.single().searchTerms shouldContainExactly listOf("novelbin name", "https://novelbin.test")
        rows.single().searchIds shouldContainExactly listOf("novelbin")
    }

    private fun List<BrowseExtensionRow>.sectionsById() =
        map { (it.key as ExtensionKey.Novel).pluginId to it.section }

    private fun update(id: String) = LnPluginUpdate(entry = entry(id), installedVersion = "1.0.0")

    private fun entry(id: String, lang: String = "English") = LnRegistryEntry(
        id = id,
        name = "$id name",
        version = "2.0.0",
        site = "https://$id.test",
        lang = lang,
        url = "https://$id.test/plugin.js",
    )

    private fun failure(id: String) = LnPluginLoadFailure(
        url = "https://$id.test/plugin.js",
        pluginId = id,
        name = "$id name",
        iconUrl = null,
        lang = "en",
        version = "1.0.0",
        customCssUrl = null,
        reason = LnPluginLoadFailure.Reason.Malformed,
    )

    private fun source(pluginId: String) = mockk<NovelSource> {
        every { id } returns pluginId
        every { name } returns "$pluginId name"
        every { site } returns "https://$pluginId.test"
        every { lang } returns "en"
        every { iconUrl } returns null
    }
}
