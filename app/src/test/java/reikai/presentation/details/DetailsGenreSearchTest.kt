package reikai.presentation.details

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen

/** A genre tapped on a details page returns to its own source's catalogue, and never another's. */
class DetailsGenreSearchTest {

    private val own = SourceKey.Manga(1L)

    @Test
    fun `the series' own catalogue is the target`() {
        val catalogue = EntryCatalogueScreen(own)
        listOf(EntryCatalogueScreen(SourceKey.Manga(2L)), catalogue).catalogueOf(own) shouldBe catalogue
    }

    @Test
    fun `another source's catalogue is never the target`() {
        listOf(EntryCatalogueScreen(SourceKey.Manga(2L))).catalogueOf(own) shouldBe null
    }

    @Test
    fun `a novel catalogue is never a manga's target`() {
        listOf(EntryCatalogueScreen(SourceKey.Novel("1"))).catalogueOf(own) shouldBe null
    }

    @Test
    fun `the nearest of two catalogues of the source is the target`() {
        val nearest = EntryCatalogueScreen(own)
        listOf(EntryCatalogueScreen(own), nearest).catalogueOf(own) shouldBe nearest
    }
}
