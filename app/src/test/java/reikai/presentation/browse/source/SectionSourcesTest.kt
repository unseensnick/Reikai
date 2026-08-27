package reikai.presentation.browse.source

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey

/**
 * The Sources list is assembled once for both content types, so these pin what a user sees in it:
 * which sections exist, in what order, and which section a source lands in.
 */
class SectionSourcesTest {

    @Test
    fun `sections run last used, then pinned, then languages, with no language last`() {
        val items = sectionSources(
            listOf(
                manga("Zed", lang = "en"),
                manga("Anna", lang = ""),
                manga("Bea", lang = "de", isPinned = true),
                manga("Cy", lang = "en", isUsedLast = true),
            ),
        )

        items.headers() shouldBe listOf("last_used", "pinned", "en", "")
    }

    @Test
    fun `a manga source and a novel source share their language section`() {
        val items = sectionSources(listOf(novel("Novel Fire", lang = "en"), manga("Asura", lang = "en")))

        items.headers() shouldBe listOf("en")
        items.namesUnder("en") shouldBe listOf("Asura", "Novel Fire")
    }

    @Test
    fun `the last-used source still appears in its own language section`() {
        // The provider hands the flagged row over as a second copy, so the source is in both places.
        val rows = manga("Comick", lang = "en").let { listOf(it, it.copy(isUsedLast = true)) }

        val items = sectionSources(rows)

        items.namesUnder("last_used") shouldBe listOf("Comick")
        items.namesUnder("en") shouldBe listOf("Comick")
    }

    @Test
    fun `a pinned source leaves its language section`() {
        val items = sectionSources(listOf(manga("Asura", lang = "en", isPinned = true), manga("Bato", lang = "en")))

        items.namesUnder("pinned") shouldBe listOf("Asura")
        items.namesUnder("en") shouldBe listOf("Bato")
    }

    @Test
    fun `rows within a section are ordered by name regardless of case`() {
        val items =
            sectionSources(listOf(manga("zed", lang = "en"), manga("Anna", lang = "en"), manga("bea", lang = "en")))

        items.namesUnder("en") shouldBe listOf("Anna", "bea", "zed")
    }

    @Test
    fun `an empty list has no sections`() {
        sectionSources(emptyList()) shouldBe emptyList()
    }

    private var nextId = 0L

    private fun manga(name: String, lang: String, isPinned: Boolean = false, isUsedLast: Boolean = false) =
        BrowseSourceRow(SourceKey.Manga(nextId++), name, lang, isPinned, isUsedLast, source = Unit)

    private fun novel(name: String, lang: String, isPinned: Boolean = false, isUsedLast: Boolean = false) =
        BrowseSourceRow(SourceKey.Novel(name), name, lang, isPinned, isUsedLast, source = Unit)

    private fun List<SourcesListItem>.headers() =
        filterIsInstance<SourcesListItem.Header>().map { it.key }

    private fun List<SourcesListItem>.namesUnder(header: String): List<String> =
        dropWhile { !(it is SourcesListItem.Header && it.key == header) }
            .drop(1)
            .takeWhile { it is SourcesListItem.Row }
            .map { (it as SourcesListItem.Row).row.name }
}
