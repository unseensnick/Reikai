package reikai.presentation.details

import eu.kanade.tachiyomi.ui.home.TabEvent
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import mihon.core.navigation.HomeRoute
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.presentation.OtherTestRoute
import reikai.presentation.RecordingResultBus
import reikai.presentation.browse.catalogue.BrowseSearchEvent
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.resultKey
import reikai.presentation.testNavigator

/** A genre tapped on a details page returns to its own source's catalogue, and never another's. */
class DetailsGenreSearchTest {

    private val own = SourceKey.Manga(1L)
    private val recorder = RecordingResultBus()

    @Test
    fun `the series' own catalogue is the target`() {
        val catalogue = EntryCatalogueRoute(own)
        listOf(EntryCatalogueRoute(SourceKey.Manga(2L)), catalogue).catalogueOf(own) shouldBe catalogue
    }

    @Test
    fun `another source's catalogue is never the target`() {
        listOf(EntryCatalogueRoute(SourceKey.Manga(2L))).catalogueOf(own) shouldBe null
    }

    @Test
    fun `a novel catalogue is never a manga's target`() {
        listOf(EntryCatalogueRoute(SourceKey.Novel("1"))).catalogueOf(own) shouldBe null
    }

    @Test
    fun `the nearest of two catalogues of the source is the target`() {
        val nearest = EntryCatalogueRoute(own)
        // Equal routes, so only identity tells the nearest from the farther one, as the pop does.
        listOf(EntryCatalogueRoute(own), nearest).catalogueOf(own) shouldBeSameInstanceAs nearest
    }

    @Test
    fun `a library search walks back past a catalogue to the library and searches it`() {
        val stack = testNavigator(HomeRoute, EntryCatalogueRoute(own), OtherTestRoute)
        stack.searchLibraryFromDetails(recorder.bus, "query", ContentType.NOVELS)

        val search = TabEvent.Library(query = "query", contentType = ContentType.NOVELS)
        (stack.current to recorder.sent) shouldBe (HomeRoute to listOf(resultKey<TabEvent>() to search))
    }

    @Test
    fun `a genre with no catalogue of its source behind it searches the library`() {
        val stack =
            testNavigator(HomeRoute, EntryCatalogueRoute(SourceKey.Manga(2L)), OtherTestRoute)
        stack.searchGenreFromDetails(recorder.bus, "Action", own, ContentType.MANGA)

        recorder.sent shouldBe
            listOf(resultKey<TabEvent>() to TabEvent.Library(query = "Action", contentType = ContentType.MANGA))
    }

    @Test
    fun `a genre returns to its source's catalogue and searches it as a genre`() {
        val catalogue = EntryCatalogueRoute(own)
        val stack = testNavigator(HomeRoute, catalogue, OtherTestRoute)
        stack.searchGenreFromDetails(recorder.bus, "Action", own, ContentType.MANGA)

        (stack.current to recorder.sent) shouldBe
            (catalogue to listOf(resultKey<BrowseSearchEvent>() to BrowseSearchEvent("Action", genre = true)))
    }

    @Test
    fun `a source with no filters searches the genre as text`() {
        val stack = testNavigator(HomeRoute, EntryCatalogueRoute(own), OtherTestRoute)
        stack.searchGenreFromDetails(recorder.bus, "Action", own, ContentType.MANGA, filterable = false)

        recorder.sent shouldBe listOf(resultKey<BrowseSearchEvent>() to BrowseSearchEvent("Action", genre = false))
    }

    @Test
    fun `nothing is sent when the library is not on the stack`() {
        val stack = testNavigator(OtherTestRoute, OtherTestRoute)
        stack.searchLibraryFromDetails(recorder.bus, "query", ContentType.MANGA)

        recorder.sent shouldBe emptyList()
    }
}
