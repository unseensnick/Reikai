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
import reikai.domain.entry.EntryId
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.host.NovelItem
import reikai.presentation.migrate.flow.MigrationPickHandoff
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/** A novel picked as a migration target is stored first, and one that cannot be stored says so. */
class NovelMigrationPickTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    private val handoff = MigrationPickHandoff()

    @Test
    fun `a pick that cannot be stored is reported`() = runTest {
        var reported = false

        pick(onPicked = {}, onUnavailable = { reported = true })

        reported shouldBe true
    }

    @Test
    fun `a pick that cannot be stored stays on the catalogue`() = runTest {
        var picked = false

        pick(onPicked = { picked = true }, onUnavailable = {})

        picked shouldBe false
    }

    @Test
    fun `a pick that cannot be stored hands nothing to the migration screen`() = runTest {
        pick(onPicked = {}, onUnavailable = {})

        handoff.take(EntryId.Novel(ENTRY_ID)) shouldBe null
    }

    /** Picks, then waits for the pick's own work only: the model also runs collectors that never end. */
    private suspend fun pick(onPicked: () -> Unit, onUnavailable: () -> Unit) {
        val model = model()
        val scope = model.viewModelScope.coroutineContext.job
        val running = scope.children.toSet()
        model.pickAsMigrationTarget(ITEM, ENTRY_ID, onPicked, onUnavailable)
        (scope.children.toSet() - running).forEach { it.join() }
    }

    private fun model(): NovelBrowseViewModel {
        val store = EmittingPreferenceStore()
        return NovelBrowseViewModel(
            sourceId = SOURCE_ID,
            initialQuery = "",
            startLatest = false,
            installer = mockk(relaxed = true),
            manager = mockk(relaxed = true),
            novelRepository = mockk(relaxed = true) {
                every { getFavoritedKeysAsFlow() } returns flowOf(emptySet())
            },
            libraryAdder = mockk(relaxed = true) { coEvery { materialize(any(), any()) } returns null },
            pickHandoff = handoff,
            reikaiSourcePreferences = ReikaiSourcePreferences(store),
            sourcePreferences = SourcePreferences(store),
            getIncognitoState = mockk(relaxed = true),
            libraryPreferences = LibraryPreferences(store),
        )
    }

    private companion object {
        const val SOURCE_ID = "src"
        const val ENTRY_ID = 7L
        val ITEM = NovelItem(name = "a novel", path = "/a-novel", cover = null)
    }
}
