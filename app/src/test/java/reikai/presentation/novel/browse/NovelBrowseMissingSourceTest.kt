package reikai.presentation.novel.browse

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
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.source.NovelSourceManager
import reikai.presentation.browse.EntryBulkFavoriteViewModel
import reikai.presentation.browse.catalogue.EntryBrowseScreenState
import reikai.presentation.browse.catalogue.NovelBrowseAdapter
import reikai.presentation.migrate.flow.MigrationPickHandoff
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/** A novel catalogue whose source is gone says so the way a manga one does, by the source's name. */
class NovelBrowseMissingSourceTest {

    private val dispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() = Dispatchers.setMain(dispatcher)

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a source that is not installed shows as missing under its last known name`() = runTest {
        val store = EmittingPreferenceStore()
        val manager = mockk<NovelSourceManager>(relaxed = true)
        coEvery { manager.get(SOURCE_ID) } returns null
        coEvery { manager.nameOf(SOURCE_ID) } returns "Gone plugin"
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
        val bulk = mockk<NovelBulkFavoriteViewModel> {
            every { state } returns MutableStateFlow(EntryBulkFavoriteViewModel.State())
        }

        val state = NovelBrowseAdapter(model, bulk, SOURCE_ID).state.first { it != EntryBrowseScreenState.Loading }

        state shouldBe EntryBrowseScreenState.SourceMissing("Gone plugin")
    }

    private companion object {
        const val SOURCE_ID = "gone"
    }
}
