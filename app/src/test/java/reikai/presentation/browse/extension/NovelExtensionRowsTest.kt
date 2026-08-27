package reikai.presentation.browse.extension

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
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
            installed = listOf(source("novelbin"), source("royalroad")),
            available = emptyList(),
        )

        rows.sectionsById() shouldContainExactly listOf(
            "novelbin" to ExtensionSection.Updates,
            "royalroad" to ExtensionSection.Installed,
        )
    }

    @Test
    fun `an installed plugin still offered by a repo is not listed again as available`() {
        // The model matches those two lists by URL, so a plugin reachable at a second URL is in both.
        val rows = novelExtensionRows(
            updates = emptyList(),
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
            installed = emptyList(),
            available = listOf(entry("uno", lang = "Español"), entry("dos", lang = "es")),
        )

        rows.map { it.section }.distinct() shouldBe listOf(ExtensionSection.Available("es"))
    }

    @Test
    fun `a row carries its site and id to the search box`() {
        val rows = novelExtensionRows(emptyList(), listOf(source("novelbin")), emptyList())

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

    private fun source(pluginId: String) = mockk<NovelSource> {
        every { id } returns pluginId
        every { name } returns "$pluginId name"
        every { site } returns "https://$pluginId.test"
        every { lang } returns "en"
        every { iconUrl } returns null
    }
}
