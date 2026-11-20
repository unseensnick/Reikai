package reikai.presentation.browse.catalogue

import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.tachiyomi.ui.browse.source.browse.BrowseSourceViewModel
import exh.source.ExhPreferences
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
import reikai.domain.novel.FavoritedNovels
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.BulkFavoriteViewModel
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.novel.browse.NovelBrowseViewModel
import reikai.presentation.novel.browse.NovelBulkFavoriteViewModel
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.source.model.StubSource

/**
 * A catalogue whose source is no longer installed says so under the source's last known name, for
 * both types, instead of loading forever. The novel-only failure, a plugin that threw while loading,
 * is NovelBrowseMissingSourceTest's.
 */
class MissingSourceConformanceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a source that is not installed shows as missing under its last known name`(probe: MissingSourceProbe) =
        runTest {
            val settled = probe.open().state.first { it != EntryBrowseScreenState.Loading }

            settled shouldBe EntryBrowseScreenState.SourceMissing(probe.label)
        }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaMissingSourceProbe(), NovelMissingSourceProbe())
    }
}

/** A catalogue opened on a source that is gone, and the label its missing state should carry. */
interface MissingSourceProbe {
    val label: String
    fun open(): EntryBrowseBehavior
}

private class MangaMissingSourceProbe : MissingSourceProbe {

    override fun toString() = "manga"

    // A stub is what the source manager hands back for an extension that is gone.
    override val label = "Gone source (EN)"

    override fun open(): EntryBrowseBehavior {
        val store = EmittingPreferenceStore()
        val model = BrowseSourceViewModel(
            appScope = testAppScope(),
            sourceId = 1L,
            listingQuery = mangaListingQuery(startLatest = false, initialQuery = null),
            sourceManager = mockk(relaxed = true) {
                coEvery { getOrStub(1L) } returns StubSource(id = 1L, lang = "en", name = "Gone source")
            },
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
        return MangaBrowseAdapter(model, bulk, model.viewModelScope)
    }
}

private class NovelMissingSourceProbe : MissingSourceProbe {

    override fun toString() = "novels"

    override val label = "Gone plugin"

    override fun open(): EntryBrowseBehavior {
        val store = EmittingPreferenceStore()
        // Stubbed off the mock: `get` inside a mockk block binds to MockK's own dynamic-call helper.
        val manager = mockk<NovelSourceManager>(relaxed = true)
        coEvery { manager.get(SOURCE_ID) } returns null
        coEvery { manager.nameOf(SOURCE_ID) } returns label
        val model = NovelBrowseViewModel(
            appScope = testAppScope(),
            sourceId = SOURCE_ID,
            initialQuery = "",
            startLatest = false,
            installer = mockk(relaxed = true) { every { failures } returns MutableStateFlow(emptyMap()) },
            manager = manager,
            novelRepository = mockk(relaxed = true) {
                every { getFavoritedKeysAsFlow() } returns flowOf(FavoritedNovels.None)
            },
            libraryAdder = mockk(relaxed = true),
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            sourcePreferences = SourcePreferences(store),
            getIncognitoState = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(store),
        )
        val bulk = mockk<NovelBulkFavoriteViewModel> {
            every { state } returns MutableStateFlow(EntryBulkFavoriteViewModel.State())
        }
        return NovelBrowseAdapter(model, bulk, SOURCE_ID, model.viewModelScope)
    }

    private companion object {
        const val SOURCE_ID = "gone"
    }
}
