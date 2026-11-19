package reikai.presentation.details

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.home.TabEvent
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.presentation.RecordingResultBus
import reikai.presentation.browse.catalogue.BrowseSearchEvent
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.resultKey

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
        listOf(EntryCatalogueRoute(own), nearest).catalogueOf(own) shouldBe nearest
    }

    @Test
    fun `a library search walks back past a catalogue to the library and searches it`() {
        val stack = mutableStateStackOf(HomeScreen, EntryCatalogueRoute(own), mockk<Screen>(), minSize = 1)
        stack.searchLibraryFromDetails(recorder.bus, "query", ContentType.NOVELS)

        val search = TabEvent.Library(query = "query", contentType = ContentType.NOVELS)
        (stack.lastItemOrNull to recorder.sent) shouldBe (HomeScreen to listOf(resultKey<TabEvent>() to search))
    }

    @Test
    fun `a genre with no catalogue of its source behind it searches the library`() {
        val stack =
            mutableStateStackOf(HomeScreen, EntryCatalogueRoute(SourceKey.Manga(2L)), mockk<Screen>(), minSize = 1)
        stack.searchGenreFromDetails(recorder.bus, "Action", own, ContentType.MANGA)

        recorder.sent shouldBe
            listOf(resultKey<TabEvent>() to TabEvent.Library(query = "Action", contentType = ContentType.MANGA))
    }

    @Test
    fun `a genre returns to its source's catalogue and searches it as a genre`() {
        val catalogue = EntryCatalogueRoute(own)
        val stack = mutableStateStackOf(HomeScreen, catalogue, mockk<Screen>(), minSize = 1)
        stack.searchGenreFromDetails(recorder.bus, "Action", own, ContentType.MANGA)

        (stack.lastItemOrNull to recorder.sent) shouldBe
            (catalogue to listOf(resultKey<BrowseSearchEvent>() to BrowseSearchEvent("Action", genre = true)))
    }

    @Test
    fun `a source with no filters searches the genre as text`() {
        val stack = mutableStateStackOf(HomeScreen, EntryCatalogueRoute(own), mockk<Screen>(), minSize = 1)
        stack.searchGenreFromDetails(recorder.bus, "Action", own, ContentType.MANGA, filterable = false)

        recorder.sent shouldBe listOf(resultKey<BrowseSearchEvent>() to BrowseSearchEvent("Action", genre = false))
    }

    @Test
    fun `nothing is sent when the library is not on the stack`() {
        val stack = mutableStateStackOf(mockk<Screen>(), mockk<Screen>(), minSize = 1)
        stack.searchLibraryFromDetails(recorder.bus, "query", ContentType.MANGA)

        recorder.sent shouldBe emptyList()
    }
}
