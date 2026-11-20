package reikai.presentation.novel.browse

import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
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
import org.junit.jupiter.api.Test
import reikai.domain.novel.FavoritedNovels
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.install.LnPluginInstaller
import reikai.novel.install.LnPluginLoadFailure
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.browse.catalogue.EntryBrowseScreenState
import reikai.presentation.browse.catalogue.NovelBrowseAdapter
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR

/**
 * A novel catalogue whose plugin is installed but threw while loading says why and offers a retry. A source
 * that is gone altogether is MissingSourceConformanceTest's, over both types.
 */
class NovelBrowseMissingSourceTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = EmittingPreferenceStore()
    private val loadFailures = MutableStateFlow<Map<String, LnPluginLoadFailure>>(emptyMap())
    private val installer = mockk<LnPluginInstaller>(relaxed = true) { every { failures } returns loadFailures }
    private val manager = mockk<NovelSourceManager>(relaxed = true).also { manager ->
        coEvery { manager.get(SOURCE_ID) } returns null
        coEvery { manager.nameOf(SOURCE_ID) } returns "Gone plugin"
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `an installed plugin that threw while loading shows why`() = runTest {
        loadFailures.value = mapOf(FAILURE.url to FAILURE)

        val state = adapter().settled() as EntryBrowseScreenState.SourceLoadFailed

        Triple(state.label, state.description, state.message) shouldBe
            Triple("Gone plugin", MR.strings.ext_load_failed_message, "ReferenceError: x")
    }

    @Test
    fun `retrying a plugin that loads now opens its catalogue`() = runTest {
        loadFailures.value = mapOf(FAILURE.url to FAILURE)
        val adapter = adapter()
        val failed = adapter.settled() as EntryBrowseScreenState.SourceLoadFailed
        coEvery { manager.get(SOURCE_ID) } returns mockk<NovelSource>(relaxed = true)

        failed.retry.run()

        val next = adapter.state.first {
            it != EntryBrowseScreenState.Loading && it !is EntryBrowseScreenState.SourceLoadFailed
        }

        next::class shouldBe EntryBrowseScreenState.Loaded::class
    }

    private fun adapter(): NovelBrowseAdapter {
        val model = NovelBrowseViewModel(
            appScope = testAppScope(),
            sourceId = SOURCE_ID,
            initialQuery = "",
            startLatest = false,
            installer = installer,
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

    private suspend fun NovelBrowseAdapter.settled() = state.first { it != EntryBrowseScreenState.Loading }

    private companion object {
        const val SOURCE_ID = "gone"
        val FAILURE = LnPluginLoadFailure(
            url = "https://example.org/gone.js",
            pluginId = SOURCE_ID,
            name = "Gone plugin",
            iconUrl = null,
            lang = null,
            version = "1.0.0",
            customCssUrl = null,
            reason = LnPluginLoadFailure.Reason.Failed("ReferenceError: x", "trace"),
        )
    }
}
