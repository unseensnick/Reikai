package reikai.presentation.migrate.flow

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.jupiter.api.Test

/** A row's work belongs to the list model: it ends with the model, and a row ending leaves it alone. */
class MigratingEntryRowTest {

    private val model = Job()
    private val row = MigratingEntryRow(migrationEntry(1), model, StandardTestDispatcher())

    @Test
    fun `cancelling the model's job cancels the row`() {
        model.cancel()

        row.scope.isActive shouldBe false
    }

    @Test
    fun `cancelling a row leaves the model running`() {
        row.scope.cancel()

        model.isActive shouldBe true
    }
}
