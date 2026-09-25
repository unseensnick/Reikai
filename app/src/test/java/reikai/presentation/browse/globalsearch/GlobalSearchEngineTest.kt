package reikai.presentation.browse.globalsearch

import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.source.ReikaiSourcePreferences
import reikai.domain.source.SourceKey
import reikai.presentation.recents.EmittingPreferenceStore

/**
 * Global search remembers which sources it last covered, and says why a finished search shows nothing
 * rather than leaving a blank screen.
 */
class GlobalSearchEngineTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = EmittingPreferenceStore()
    private val preferences = ReikaiSourcePreferences(store)

    /** Answers with one source under All and none under Pinned, recording the filter it was asked for. */
    private class FakeProvider : GlobalSearchProvider {
        val asked = mutableListOf<SearchSourceFilter>()
        override val contentType = ContentType.MANGA
        override suspend fun sources(filter: SearchSourceFilter): List<BrowseSearchRow> {
            asked += filter
            return if (filter == SearchSourceFilter.All) listOf(row()) else emptyList()
        }
        override suspend fun search(row: BrowseSearchRow, query: String): List<Any> = emptyList()
    }

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun engine(provider: GlobalSearchProvider = FakeProvider(), initial: SearchSourceFilter? = null) =
        GlobalSearchEngine(listOf(provider), "query", null, initial, preferences, SourcePreferences(store))

    @Test
    fun `a search opens on the sources last chosen`() = runTest(dispatcher) {
        preferences.globalSearchSourceFilter.set(SearchSourceFilter.All)
        val provider = FakeProvider()
        engine(provider).state.first { it.searched }
        provider.asked.first() shouldBe SearchSourceFilter.All
    }

    @Test
    fun `an unscoped search, as shared text opens, starts on the Browse content type`() = runTest(dispatcher) {
        preferences.browseContentType.set(ContentType.NOVELS)
        engine().state.first { it.searched }.contentType shouldBe ContentType.NOVELS
    }

    @Test
    fun `choosing the sources remembers them for the next search`() = runTest(dispatcher) {
        engine().setSourceFilter(SearchSourceFilter.All)
        preferences.globalSearchSourceFilter.get() shouldBe SearchSourceFilter.All
    }

    @Test
    fun `a deep link's sources apply to that search`() = runTest(dispatcher) {
        val provider = FakeProvider()
        engine(provider, initial = SearchSourceFilter.All).state.first { it.searched }
        provider.asked.first() shouldBe SearchSourceFilter.All
    }

    @Test
    fun `a deep link's sources are not remembered`() = runTest(dispatcher) {
        engine(initial = SearchSourceFilter.All).state.first { it.searched }
        preferences.globalSearchSourceFilter.get() shouldBe SearchSourceFilter.PinnedOnly
    }

    @Test
    fun `nothing pinned to search says so`() {
        GlobalSearchEngine.State(query = "q", searched = true).emptyReason shouldBe
            GlobalSearchEngine.EmptyReason.NoPinnedSources
    }

    @Test
    fun `no source at all under All is not blamed on pinning`() {
        GlobalSearchEngine.State(query = "q", sourceFilter = SearchSourceFilter.All, searched = true)
            .emptyReason shouldBe null
    }

    @Test
    fun `every source answering empty with has-results on says no results`() {
        GlobalSearchEngine.State(
            query = "q",
            searched = true,
            onlyShowHasResults = true,
            rows = listOf(row(EntrySearchState.Success(emptyList()))),
        ).emptyReason shouldBe GlobalSearchEngine.EmptyReason.NoResults
    }

    @Test
    fun `sources still searching are not reported empty yet`() {
        GlobalSearchEngine.State(query = "q", searched = true, onlyShowHasResults = true, rows = listOf(row()))
            .emptyReason shouldBe null
    }

    @Test
    fun `a search not started yet is not reported empty`() {
        GlobalSearchEngine.State(query = "q").emptyReason shouldBe null
    }

    @Test
    fun `a blank query is not reported empty`() {
        GlobalSearchEngine.State(query = "", searched = true).emptyReason shouldBe null
    }

    private companion object {
        fun row(state: EntrySearchState = EntrySearchState.Loading) = BrowseSearchRow(
            key = SourceKey.Manga(1L),
            name = "Site",
            lang = "en",
            isPinned = true,
            state = state,
            source = Unit,
        )
    }
}
