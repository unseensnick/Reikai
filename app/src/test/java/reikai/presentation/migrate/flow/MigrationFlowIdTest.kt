package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/** The flow id a deep pick is keyed by lives in the asking screen, so it is saved with it. */
class MigrationFlowIdTest {

    @Test
    fun `the list screen keeps its flow id through a save and restore`() {
        val screen = EntryMigrationListRoute(ContentType.MANGA, listOf(1L, 2L), extraQuery = null)

        (screen.roundTrip() as EntryMigrationListRoute).flowId shouldBe screen.flowId
    }

    @Test
    fun `the search screen keeps its flow id through a save and restore`() {
        val screen = EntryMigrationSearchRoute(ContentType.NOVELS, 1L, extraQuery = null)

        (screen.roundTrip() as EntryMigrationSearchRoute).flowId shouldBe screen.flowId
    }

    @Test
    fun `two flows get different ids`() {
        val first = EntryMigrationListRoute(ContentType.MANGA, listOf(1L), extraQuery = null)
        val second = EntryMigrationListRoute(ContentType.MANGA, listOf(1L), extraQuery = null)

        (first.flowId == second.flowId) shouldBe false
    }

    @Test
    fun `the deep picker asks for the entry under the flow's id`() {
        val stack = mutableStateStackOf(mockk<Screen>(), minSize = 1)
        val entry = migrationEntry(4)

        openDeepPicker(stack, entry, "7", "query", flowId = 9137L)

        (stack.lastItemOrNull as EntryCatalogueRoute).migrateFor shouldBe MigrationPickFor(entry.id, 9137L)
    }

    private fun Any.roundTrip(): Any {
        val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(this) } }
        return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
    }
}
