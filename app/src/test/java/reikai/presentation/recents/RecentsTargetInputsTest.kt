package reikai.presentation.recents

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * What clears the continue-reading memo: the tables a target is resolved from, and the preferences the
 * resolve reads. A target re-sorted or hidden by a preference has to be resolved again like one whose
 * chapter was written.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsTargetInputsTest {

    private val store = EmittingPreferenceStore()

    private fun TestScope.countEmissions(signal: Flow<Unit>): () -> Int {
        var fired = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { signal.collect { fired++ } }
        return { fired }
    }

    @Test
    fun `a change to a preference the target reads reaches the signal`() = runTest {
        val hidden = store.getStringSet("hidden", emptySet())
        val fired = countEmissions(recentsTargetInputs(emptyFlow(), hidden))

        hidden.set(setOf("chapter"))

        fired() shouldBe 2
    }

    @Test
    fun `a table write reaches the signal`() = runTest {
        val writes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val fired = countEmissions(recentsTargetInputs(writes, store.getBoolean("merging", true)))

        writes.emit(Unit)

        fired() shouldBe 2
    }
}
