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
    fun `multi-language sources come before every single language`() {
        // Sorting the raw codes would bury "all" among them, which is where it used to land.
        val items = sectionSources(listOf(manga("Anna", lang = "af"), manga("Bea", lang = "all")))

        items.headers() shouldBe listOf("all", "af")
    }

    @Test
    fun `languages are ordered by their own name for themselves, not by their code`() {
        // ar reads as the Arabic endonym, so it follows Deutsch even though the code precedes de.
        val items = sectionSources(listOf(manga("Anna", lang = "ar"), manga("Bea", lang = "de")))

        items.headers() shouldBe listOf("de", "ar")
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
    fun `the local source's group sinks below every real language`() {
        // "other" names no language, so sorting it by display name would bury it under O.
        val items = sectionSources(
            listOf(manga("Zed", lang = "other"), manga("Anna", lang = "sv")),
        )

        items.headers() shouldBe listOf("sv", "other")
    }

    @Test
    fun `the last-used copy of a pinned source stays under Last used`() {
        // It carries isPinned so its row and its long-press sheet agree the source is pinned; only
        // the section check keeps it out of Pinned.
        val items = sectionSources(
            listOf(
                manga("Anna", lang = "en", isPinned = true),
                manga("Anna", lang = "en", isPinned = true, isUsedLast = true),
            ),
        )

        items.headers() shouldBe listOf("last_used", "pinned")
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
