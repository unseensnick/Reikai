package reikai.presentation.details

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import eu.kanade.tachiyomi.ui.home.HomeScreen
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.runs
import io.mockk.unmockkObject
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen

/** A genre tapped on a details page returns to its own source's catalogue, and never another's. */
class DetailsGenreSearchTest {

    private val own = SourceKey.Manga(1L)

    // HomeScreen's search channel has no buffer and nothing collects it here, so it is stubbed.
    @BeforeEach
    fun setUp() {
        mockkObject(HomeScreen)
        coEvery { HomeScreen.search(any(), any()) } just runs
    }

    @AfterEach
    fun tearDown() {
        unmockkObject(HomeScreen)
    }

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

    @Test
    fun `a library search walks back past a catalogue to the library and searches it`() = runTest {
        val stack = mutableStateStackOf(HomeScreen, EntryCatalogueScreen(own), mockk<Screen>(), minSize = 1)
        stack.searchLibraryFromDetails("query", ContentType.NOVELS)
        coVerify(exactly = 1) { HomeScreen.search("query", ContentType.NOVELS) }
    }

    @Test
    fun `a genre with no catalogue of its source behind it searches the library`() = runTest {
        val stack =
            mutableStateStackOf(HomeScreen, EntryCatalogueScreen(SourceKey.Manga(2L)), mockk<Screen>(), minSize = 1)
        stack.searchGenreFromDetails("Action", own, ContentType.MANGA)
        coVerify(exactly = 1) { HomeScreen.search("Action", ContentType.MANGA) }
    }
}
