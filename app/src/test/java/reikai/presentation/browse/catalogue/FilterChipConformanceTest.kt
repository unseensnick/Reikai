package reikai.presentation.browse.catalogue

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.migrate.flow.MigrationPickHandoff
import reikai.presentation.novel.browse.NovelBrowseState
import reikai.presentation.novel.browse.NovelBrowseViewModel
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * The Filter chip means one thing on both catalogues: a search-shaped listing is showing. The two
 * halves cannot share the code that decides it, because a manga source answers a query and its
 * filters through one call while a plugin has separate search and listing pagers, so the rule is
 * written twice and this runs both against the same scenarios instead.
 */
class FilterChipConformanceTest {

    // viewModelScope is Main-based, so both models need a Main the test controls.
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a freshly opened catalogue leaves the chip dark`(probe: FilterChipProbe) = runTest {
        probe.open()

        probe.chipActive() shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a text search lights the chip`(probe: FilterChipProbe) = runTest {
        probe.open()

        probe.search()

        probe.chipActive() shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `applying filters lights the chip`(probe: FilterChipProbe) = runTest {
        probe.open()

        probe.applyFilters()

        probe.chipActive() shouldBe true
    }

    /**
     * The case the two halves used to disagree on: resetting leaves the values at their defaults,
     * but applying them is still an applied filter, so the chip stays lit.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `resetting and re-applying keeps the chip lit`(probe: FilterChipProbe) = runTest {
        probe.open()
        probe.applyFilters()

        probe.resetFilters()
        probe.applyFilters()

        probe.chipActive() shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `switching listing clears the chip`(probe: FilterChipProbe) = runTest {
        probe.open()
        probe.applyFilters()

        probe.switchListing()

        probe.chipActive() shouldBe false
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaFilterChipProbe(), NovelFilterChipProbe())
    }
}

/**
 * Which listing a manga catalogue opens on, which is a rule of its own because upstream reads a
 * missing query as a search rather than as Popular.
 */
class MangaListingQueryTest {

    @Test
    fun `a row tap with no query opens Popular rather than a search`() {
        val query = mangaListingQuery(startLatest = false, initialQuery = null)

        BrowseSourceViewModel.Listing.valueOf(query) shouldBe BrowseSourceViewModel.Listing.Popular
    }

    @Test
    fun `the Latest button opens Latest`() {
        val query = mangaListingQuery(startLatest = true, initialQuery = null)

        BrowseSourceViewModel.Listing.valueOf(query) shouldBe BrowseSourceViewModel.Listing.Latest
    }

    @Test
    fun `a handed-over query still opens a search`() {
        val query = mangaListingQuery(startLatest = false, initialQuery = "shadow slave")

        BrowseSourceViewModel.Listing.valueOf(query).query shouldBe "shadow slave"
    }
}

/** One catalogue driven through a scenario, answering with its own chip rule. */
interface FilterChipProbe {
    suspend fun open()
    fun search()
    fun applyFilters()
    fun resetFilters()
    fun switchListing()
    fun chipActive(): Boolean
}

private class MangaFilterChipProbe : FilterChipProbe {

    override fun toString() = "manga"

    private val source = mockk<CatalogueSource>(relaxed = true) {
        every { id } returns SOURCE_ID
        every { getFilterList() } returns FilterList()
    }

    private lateinit var model: BrowseSourceViewModel

    override suspend fun open() {
        val store = EmittingPreferenceStore()
        model = BrowseSourceViewModel(
            sourceId = SOURCE_ID,
            // Opened the way the screen opens it, so the seeded listing is under test too.
            listingQuery = mangaListingQuery(startLatest = false, initialQuery = null),
            sourceManager = mockk(relaxed = true) { every { getOrStub(SOURCE_ID) } returns source },
            sourcePreferences = SourcePreferences(store),
            libraryPreferences = mockk(relaxed = true),
            getRemoteManga = mockk(relaxed = true),
            getManga = mockk(relaxed = true),
            getIncognitoState = mockk(relaxed = true),
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            mangaLibraryAdder = mockk(relaxed = true),
            getFlatMetadataById = mockk(relaxed = true),
        )
    }

    override fun search() = model.search(query = "shadow slave")

    // What the filter sheet's own apply does.
    override fun applyFilters() = model.search(filters = model.state.value.filters)

    override fun resetFilters() = model.resetFilters()

    override fun switchListing() = model.setListing(BrowseSourceViewModel.Listing.Popular)

    override fun chipActive() = model.state.value.filterChipActive()

    private companion object {
        const val SOURCE_ID = 1L
    }
}

private class NovelFilterChipProbe : FilterChipProbe {

    override fun toString() = "novels"

    private val source = mockk<NovelSource>(relaxed = true) {
        every { id } returns SOURCE_ID
        every { filters } returns null
        every { supportsLatest } returns true
    }

    private lateinit var model: NovelBrowseViewModel

    override suspend fun open() {
        val store = EmittingPreferenceStore()
        // Stubbed off the mock rather than inside a mockk block: `get` in there resolves to
        // MockK's own dynamic-call helper instead of the manager's.
        val manager = mockk<NovelSourceManager>(relaxed = true)
        every { manager.get(SOURCE_ID) } returns source
        model = NovelBrowseViewModel(
            sourceId = SOURCE_ID,
            initialQuery = "",
            startLatest = false,
            installer = mockk(relaxed = true),
            manager = manager,
            novelRepository = mockk(relaxed = true) {
                every { getFavoritedKeysAsFlow() } returns flowOf(emptySet())
            },
            libraryAdder = mockk(relaxed = true),
            pickHandoff = MigrationPickHandoff(),
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            sourcePreferences = SourcePreferences(store),
            getIncognitoState = mockk(relaxed = true),
        )
        // The plugin resolves on Dispatchers.IO, which the test scheduler cannot advance, so the
        // state is awaited. Every verb below early-returns until the source has landed.
        model.state.first { it.source != null }
    }

    override fun search() = model.search("shadow slave")

    override fun applyFilters() = model.applyFilters()

    override fun resetFilters() = model.resetFilters()

    override fun switchListing() = model.setListing(NovelBrowseState.Listing.Popular)

    override fun chipActive() = model.state.value.filterChipActive()

    private companion object {
        const val SOURCE_ID = "src"
    }
}
