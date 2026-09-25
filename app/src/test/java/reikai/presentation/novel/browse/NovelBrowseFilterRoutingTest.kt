package reikai.presentation.novel.browse

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelFilters
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.browse.catalogue.NovelBrowseAdapter
import reikai.presentation.migrate.flow.MigrationPickHandoff
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * Where applied filters go, which the two formats answer oppositely: an LNReader plugin's search takes
 * no options, so its filters page the listing, while a Mihon filter list is sent with the search.
 */
class NovelBrowseFilterRoutingTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `LNReader filters page the listing`() = runTest {
        val model = open(NovelFilters.LnSchema(JsonObject(emptyMap())))
        model.search("shadow")

        model.applyFilters()

        model.state.value.pagerInput!!.isSearch shouldBe false
    }

    @Test
    fun `applying LNReader filters drops the query their search could not take`() = runTest {
        val model = open(NovelFilters.LnSchema(JsonObject(emptyMap())))
        model.search("shadow")

        model.applyFilters()

        model.state.value.query shouldBe ""
    }

    @Test
    fun `a Mihon filter list is sent with the search`() = runTest {
        val model = open(MIHON_FILTERS)

        model.applyFilters()

        model.state.value.pagerInput!!.isSearch shouldBe true
    }

    @Test
    fun `applying a Mihon filter list keeps the query`() = runTest {
        val model = open(MIHON_FILTERS)
        model.search("shadow")

        model.applyFilters()

        model.state.value.query shouldBe "shadow"
    }

    @Test
    fun `a Mihon filter list with nothing applied pages the listing`() = runTest {
        val model = open(MIHON_FILTERS)

        model.state.value.pagerInput!!.isSearch shouldBe false
    }

    @Test
    fun `applying an edited Mihon filter list again restarts the pager`() = runTest {
        val model = open(MIHON_FILTERS)
        model.applyFilters()
        val first = model.state.value.pagerInput

        model.applyFilters()

        (model.state.value.pagerInput == first) shouldBe false
    }

    @Test
    fun `a filters-only saved search on a Mihon filter list drops the typed query`() = runTest {
        val model = open(MIHON_FILTERS)
        model.search("shadow")

        model.applySavedSearch(null)

        model.state.value.query shouldBe ""
    }

    @Test
    fun `a filters-only LNReader saved search pages the Popular listing, as its feed row does`() = runTest {
        val model = open(LN_FILTERS)
        model.setListing(NovelListing.Latest)

        model.applySavedSearch(null)

        model.state.value.pagerInput!!.listing shouldBe NovelListing.Popular
    }

    @Test
    fun `an LNReader saved search with a query leaves the defaults in the filter sheet`() = runTest {
        // The query ran alone, so saved filters left in the sheet disagreed with the results.
        val model = open(LN_FILTERS)
        model.setFilterState(NovelFilterState.LnValues(mapOf("genre" to JsonPrimitive("action"))))

        model.applySavedSearch("shadow")

        model.state.value.filterDraft shouldBe LN_FILTERS.defaultState()
    }

    @Test
    fun `an LNReader search with a query is saved without filters it cannot run`() = runTest {
        val model = open(LN_FILTERS)
        model.setFilterState(NovelFilterState.LnValues(mapOf("genre" to JsonPrimitive("action"))))
        model.search("shadow")

        val bulk = mockk<NovelBulkFavoriteViewModel> {
            every { state } returns MutableStateFlow(EntryBulkFavoriteViewModel.State())
        }

        val draft = NovelBrowseAdapter(model, bulk, SOURCE_ID).captureSearch()

        draft.filtersJson shouldBe null
    }

    @Test
    fun `a genre the source offers searches with its filter and no query`() = runTest {
        val model = open(GENRE_FILTERS)
        model.search("shadow")

        model.searchGenre("Action")

        model.state.value.pagerInput!!.let { it.query to it.isSearch } shouldBe ("" to true)
    }

    @Test
    fun `a genre the source offers is applied to what the pager reads`() = runTest {
        val model = open(GENRE_FILTERS)

        model.searchGenre("Action")

        val applied = model.state.value.appliedFilters as NovelFilterState.Filters
        ((applied.list.single() as Filter.Group<*>).state.single() as Filter.CheckBox).state shouldBe true
    }

    @Test
    fun `a genre the source does not offer is reported`() = runTest {
        val model = open(GENRE_FILTERS)

        model.searchGenre("Horror") shouldBe false
    }

    @Test
    fun `a genre the source does not offer leaves the search as it was`() = runTest {
        val model = open(GENRE_FILTERS)
        model.search("shadow")

        model.searchGenre("Horror")

        model.state.value.query shouldBe "shadow"
    }

    private suspend fun open(filters: NovelFilters): NovelBrowseViewModel {
        val source = mockk<NovelSource>(relaxed = true) {
            every { id } returns SOURCE_ID
            every { this@mockk.filters } returns filters
        }
        val store = EmittingPreferenceStore()
        val manager = mockk<NovelSourceManager>(relaxed = true)
        coEvery { manager.get(SOURCE_ID) } returns source
        val model = NovelBrowseViewModel(
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
            libraryPreferences = LibraryPreferences(store),
        )
        // The source resolves on Dispatchers.IO, which the test scheduler cannot advance.
        model.state.first { it.source != null }
        return model
    }

    private companion object {
        const val SOURCE_ID = "src"
        val LN_FILTERS = NovelFilters.LnSchema(JsonObject(emptyMap()))
        val MIHON_FILTERS = NovelFilters.FilterListSchema { FilterList(object : Filter.Text("Author") {}) }
        val GENRE_FILTERS = NovelFilters.FilterListSchema {
            FilterList(
                object : Filter.Group<Filter.CheckBox>("Genres", listOf(object : Filter.CheckBox("Action") {})) {},
            )
        }
    }
}
