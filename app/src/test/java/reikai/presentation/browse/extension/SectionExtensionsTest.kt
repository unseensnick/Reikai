package reikai.presentation.browse.extension

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The Extensions list is assembled once for both content types, so these pin what a user sees in
 * it: which sections exist, in what order, and what the search box keeps.
 */
class SectionExtensionsTest {

    @Test
    fun `sections run updates, then installed, then what is available`() {
        val items = section(
            row("Zed", ExtensionSection.Available("en")),
            row("Anna", ExtensionSection.Installed),
            row("Bea", ExtensionSection.Updates),
        )

        items.headers() shouldBe listOf(
            ExtensionSection.Updates,
            ExtensionSection.Installed,
            ExtensionSection.Available("en"),
        )
    }

    @Test
    fun `available splits by language, multi first and the rest by their own name`() {
        // ar reads as the Arabic endonym, so it follows Deutsch even though the code precedes de.
        val items = section(
            row("Zed", ExtensionSection.Available("ar")),
            row("Anna", ExtensionSection.Available("de")),
            row("Cy", ExtensionSection.Available("all")),
        )

        items.headers() shouldBe listOf(
            ExtensionSection.Available("all"),
            ExtensionSection.Available("de"),
            ExtensionSection.Available("ar"),
        )
    }

    @Test
    fun `a manga extension and a novel plugin share the updates section`() {
        val items = section(
            novelRow("Novel Fire", ExtensionSection.Updates),
            row("Asura", ExtensionSection.Updates),
        )

        items.headers() shouldBe listOf(ExtensionSection.Updates)
        items.namesUnder(ExtensionSection.Updates) shouldBe listOf("Asura", "Novel Fire")
    }

    @Test
    fun `rows within a section are ordered by name regardless of case`() {
        val items = section(
            row("zed", ExtensionSection.Installed),
            row("Anna", ExtensionSection.Installed),
            row("bea", ExtensionSection.Installed),
        )

        items.namesUnder(ExtensionSection.Installed) shouldBe listOf("Anna", "bea", "zed")
    }

    @Test
    fun `an empty query keeps every row`() {
        matchesExtensionQuery(row("Asura", ExtensionSection.Installed), null) shouldBe true
        matchesExtensionQuery(row("Asura", ExtensionSection.Installed), "  ") shouldBe true
    }

    @Test
    fun `a query matches part of a name`() {
        matchesExtensionQuery(row("Asura Scans", ExtensionSection.Installed), "sura") shouldBe true
    }

    @Test
    fun `a comma-separated query widens rather than narrows`() {
        val asura = row("Asura Scans", ExtensionSection.Installed)

        matchesExtensionQuery(asura, "nothing, sura") shouldBe true
        matchesExtensionQuery(asura, "nothing, neither") shouldBe false
    }

    @Test
    fun `an id matches only as the whole query`() {
        // Otherwise searching "1" would drag in every source whose id merely contains a 1.
        val row = row("Asura Scans", ExtensionSection.Installed, ids = listOf("1234"))

        matchesExtensionQuery(row, "1234") shouldBe true
        matchesExtensionQuery(row, "123") shouldBe false
    }

    private fun section(vararg rows: BrowseExtensionRow) = sectionExtensions(rows.toList())

    private fun row(name: String, section: ExtensionSection, ids: List<String> = emptyList()) =
        BrowseExtensionRow(
            key = ExtensionKey.Manga(name),
            name = name,
            section = section,
            searchTerms = listOf(name),
            searchIds = ids,
            payload = Unit,
        )

    private fun novelRow(name: String, section: ExtensionSection) =
        BrowseExtensionRow(
            key = ExtensionKey.Novel(name),
            name = name,
            section = section,
            searchTerms = listOf(name),
            searchIds = emptyList(),
            payload = Unit,
        )

    private fun List<ExtensionsListItem>.headers() =
        filterIsInstance<ExtensionsListItem.Header>().map { it.section }

    private fun List<ExtensionsListItem>.namesUnder(section: ExtensionSection): List<String> =
        dropWhile { !(it is ExtensionsListItem.Header && it.section == section) }
            .drop(1)
            .takeWhile { it is ExtensionsListItem.Row }
            .map { (it as ExtensionsListItem.Row).row.name }
}
