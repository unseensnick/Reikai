package reikai.presentation.migrate.flow

import androidx.navigation3.runtime.NavKey
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.library.ContentType
import reikai.presentation.OtherTestRoute
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.roundTrip
import reikai.presentation.testNavigator

/** The flow id a deep pick is keyed by lives in the asking route, so it is saved with it. */
@RunWith(RobolectricTestRunner::class)
class MigrationFlowIdTest {

    @Test
    fun `the list screen keeps its flow id through a save and restore`() {
        val screen = EntryMigrationListRoute(ContentType.MANGA, listOf(1L, 2L), extraQuery = null)

        (roundTrip(screen) as EntryMigrationListRoute).flowId shouldBe screen.flowId
    }

    @Test
    fun `the search screen keeps its flow id through a save and restore`() {
        val screen = EntryMigrationSearchRoute(ContentType.NOVELS, 1L, extraQuery = null)

        (roundTrip(screen) as EntryMigrationSearchRoute).flowId shouldBe screen.flowId
    }

    @Test
    fun `two flows get different ids`() {
        val first = EntryMigrationListRoute(ContentType.MANGA, listOf(1L), extraQuery = null)
        val second = EntryMigrationListRoute(ContentType.MANGA, listOf(1L), extraQuery = null)

        (first.flowId == second.flowId) shouldBe false
    }

    @Test
    fun `the deep picker asks for the entry under the flow's id`() {
        val stack = testNavigator(OtherTestRoute)
        val entry = migrationEntry(4)

        openDeepPicker(stack, entry, "7", "query", flowId = 9137L)

        (stack.current as EntryCatalogueRoute).migrateFor shouldBe MigrationPickFor(entry.id, 9137L)
    }
}
