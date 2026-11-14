package reikai.util

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore

/** Min width saved under the old 1 to 100 range reads as the 10% floor, on every path a reader takes. */
class FlooredPreferenceTest {

    private val base = EmittingPreferenceStore().getInt("min_width", 100)
    private val floored = base.floored(10)

    @Test
    fun `a value saved below the floor reads as the floor`() {
        base.set(6)
        floored.get() shouldBe 10
    }

    @Test
    fun `a value at or above the floor reads as itself`() {
        base.set(40)
        floored.get() shouldBe 40
    }

    @Test
    fun `changes emit the floored value`() = runTest {
        base.set(6)
        floored.changes().first() shouldBe 10
    }

    @Test
    fun `the state starts at the floored value`() = runTest {
        base.set(6)
        floored.stateIn(backgroundScope).value shouldBe 10
    }
}
