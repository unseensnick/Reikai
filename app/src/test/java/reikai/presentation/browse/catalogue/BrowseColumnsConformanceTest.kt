package reikai.presentation.browse.catalogue

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.migrate.flow.MigrationPickHandoff
import reikai.presentation.novel.browse.NovelBrowseViewModel
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/** Both catalogues lay their grid out in the library's column counts, read by their models. */
class BrowseColumnsConformanceTest {

    // viewModelScope is Main-based, so both models need a Main the test controls.
    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a catalogue follows the library's column counts`(probe: ColumnsProbe) = runTest {
        val store = EmittingPreferenceStore()
        LibraryPreferences(store).portraitColumns.set(3)
        LibraryPreferences(store).landscapeColumns.set(5)

        probe.open(store).columnsWhen { it.portrait == 3 } shouldBe BrowseColumns(portrait = 3, landscape = 5)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a column count changed while the catalogue is open reaches its grid`(probe: ColumnsProbe) = runTest {
        val store = EmittingPreferenceStore()
        val behavior = probe.open(store)
        behavior.columnsWhen { true }

        LibraryPreferences(store).portraitColumns.set(4)

        behavior.columnsWhen { it.portrait == 4 }.portrait shouldBe 4
    }

    private suspend fun EntryBrowseBehavior.columnsWhen(predicate: (BrowseColumns) -> Boolean): BrowseColumns =
        state.first { state ->
            val style = (state as? EntryBrowseScreenState.Loaded)?.rowStyle as? EntryBrowseRowStyle.Standard
            style != null && predicate(style.columns)
        }.let { ((it as EntryBrowseScreenState.Loaded).rowStyle as EntryBrowseRowStyle.Standard).columns }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaColumnsProbe(), NovelColumnsProbe())
    }
}

/** One catalogue opened over [EmittingPreferenceStore], through its own adapter. */
interface ColumnsProbe {
    fun open(store: EmittingPreferenceStore): EntryBrowseBehavior
}

private class MangaColumnsProbe : ColumnsProbe {

    override fun toString() = "manga"

    override fun open(store: EmittingPreferenceStore): EntryBrowseBehavior {
        val source = mockk<CatalogueSource>(relaxed = true) {
            every { id } returns 1L
            every { getFilterList() } returns FilterList()
        }
        val model = BrowseSourceViewModel(
            sourceId = 1L,
            listingQuery = mangaListingQuery(startLatest = false, initialQuery = null),
            sourceManager = mockk(relaxed = true) { coEvery { getOrStub(1L) } returns source },
            sourcePreferences = SourcePreferences(store),
            libraryPreferences = LibraryPreferences(store),
            getRemoteManga = mockk(relaxed = true),
            getManga = mockk(relaxed = true),
            getIncognitoState = mockk(relaxed = true),
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            mangaLibraryAdder = mockk(relaxed = true),
            getFlatMetadataById = mockk(relaxed = true),
        )
        val bulk = mockk<BulkFavoriteViewModel> {
            every { state } returns MutableStateFlow(EntryBulkFavoriteViewModel.State())
        }
        return MangaBrowseAdapter(model, bulk)
    }
}

private class NovelColumnsProbe : ColumnsProbe {

    override fun toString() = "novels"

    override fun open(store: EmittingPreferenceStore): EntryBrowseBehavior {
        val source = mockk<NovelSource>(relaxed = true) { every { id } returns "src" }
        // Stubbed off the mock: `get` inside a mockk block binds to MockK's own dynamic-call helper.
        val manager = mockk<NovelSourceManager>(relaxed = true)
        coEvery { manager.get("src") } returns source
        val model = NovelBrowseViewModel(
            sourceId = "src",
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
        val bulk = mockk<NovelBulkFavoriteViewModel> {
            every { state } returns MutableStateFlow(EntryBulkFavoriteViewModel.State())
        }
        return NovelBrowseAdapter(model, bulk, "src")
    }
}
