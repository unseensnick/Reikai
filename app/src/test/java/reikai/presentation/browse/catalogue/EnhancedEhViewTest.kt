package reikai.presentation.browse.catalogue

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import exh.source.ExhPreferences
import exh.source.eHentaiSourceIds
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/** The E-Hentai catalogue draws its rich rows only while the Settings switch for them is on. */
class EnhancedEhViewTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "switch on = {0}")
    @ValueSource(booleans = [true, false])
    fun `the gallery rows follow the enhanced view switch in Settings`(enabled: Boolean) = runTest {
        val store = EmittingPreferenceStore()
        ExhPreferences(store).enhancedEHentaiView().set(enabled)

        val loaded = open(store).state.first { it is EntryBrowseScreenState.Loaded } as EntryBrowseScreenState.Loaded

        (loaded.rowStyle is EntryBrowseRowStyle.Gallery) shouldBe enabled
    }

    private fun open(store: EmittingPreferenceStore): EntryBrowseBehavior {
        val sourceId = eHentaiSourceIds.first()
        val source = mockk<CatalogueSource>(relaxed = true) {
            every { id } returns sourceId
            every { getFilterList() } returns FilterList()
        }
        val model = BrowseSourceViewModel(
            sourceId = sourceId,
            listingQuery = mangaListingQuery(startLatest = false, initialQuery = null),
            sourceManager = mockk(relaxed = true) { coEvery { getOrStub(sourceId) } returns source },
            sourcePreferences = SourcePreferences(store),
            libraryPreferences = LibraryPreferences(store),
            getRemoteManga = mockk(relaxed = true),
            getManga = mockk(relaxed = true),
            getIncognitoState = mockk(relaxed = true),
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            mangaLibraryAdder = mockk(relaxed = true),
            getFlatMetadataById = mockk(relaxed = true),
            exhPreferences = ExhPreferences(store),
        )
        val bulk = mockk<BulkFavoriteViewModel> {
            every { state } returns MutableStateFlow(EntryBulkFavoriteViewModel.State())
        }
        return MangaBrowseAdapter(model, bulk)
    }
}
