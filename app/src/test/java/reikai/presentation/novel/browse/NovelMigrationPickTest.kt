package reikai.presentation.novel.browse

import androidx.lifecycle.viewModelScope
import eu.kanade.domain.source.service.SourcePreferences
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.FavoritedNovels
import reikai.domain.novel.model.Novel
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.host.NovelItem
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * A novel picked as a migration target is stored first, and the outcome waits in the state for the
 * screen to report: the store can outlive a configuration change, and only the screen drawn then holds
 * a live navigator.
 */
class NovelMigrationPickTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a pick that cannot be stored is left for the screen to report`() = runTest {
        pick(stored = null) shouldBe NovelBrowseState.MigrationPick.Unavailable
    }

    @Test
    fun `a stored pick is left for the screen as the row it was stored as`() = runTest {
        pick(stored = mockk { every { id } returns STORED_ID }) shouldBe
            NovelBrowseState.MigrationPick.Stored(STORED_ID)
    }

    @Test
    fun `a reported pick is not reported again`() = runTest {
        val model = model(stored = mockk { every { id } returns STORED_ID })
        model.awaitPick()

        model.consumeMigrationPick()

        model.state.value.migrationPick shouldBe null
    }

    private suspend fun pick(stored: Novel?): NovelBrowseState.MigrationPick? =
        model(stored).apply { awaitPick() }.state.value.migrationPick

    /** Picks, then waits for the pick's own work only: the model also runs collectors that never end. */
    private suspend fun NovelBrowseViewModel.awaitPick() {
        val scope = viewModelScope.coroutineContext.job
        val running = scope.children.toSet()
        pickAsMigrationTarget(ITEM)
        (scope.children.toSet() - running).forEach { it.join() }
    }
    private fun model(stored: Novel?): NovelBrowseViewModel {
        val store = EmittingPreferenceStore()
        return NovelBrowseViewModel(
            appScope = testAppScope(),
            sourceId = SOURCE_ID,
            initialQuery = "",
            startLatest = false,
            installer = mockk(relaxed = true),
            manager = mockk(relaxed = true),
            novelRepository = mockk(relaxed = true) {
                every { getFavoritedKeysAsFlow() } returns flowOf(FavoritedNovels.None)
            },
            libraryAdder = mockk(relaxed = true) { coEvery { materialize(any(), any()) } returns stored },
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            sourcePreferences = SourcePreferences(store),
            getIncognitoState = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(store),
        )
    }

    private companion object {
        const val SOURCE_ID = "src"
        const val STORED_ID = 7L
        val ITEM = NovelItem(name = "a novel", path = "/a-novel", cover = null)
    }
}
