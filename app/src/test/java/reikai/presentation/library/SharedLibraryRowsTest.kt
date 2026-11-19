package reikai.presentation.library

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.core.common.util.lang.SubscriberGate
import kotlin.time.Duration.Companion.seconds

/** The sharing policy every library row build runs under, over a fake build. */
@OptIn(ExperimentalCoroutinesApi::class)
class SharedLibraryRowsTest {

    private val library = MutableStateFlow(1)
    private val builds = MutableStateFlow(0)
    private val running = MutableStateFlow(0)

    private val build = library
        .onStart { running.update { it + 1 } }
        .onCompletion { running.update { it - 1 } }
        .map { value ->
            builds.update { it + 1 }
            value
        }

    private fun TestScope.shared(): Flow<Int> =
        build.sharedLibraryRows(backgroundScope, StandardTestDispatcher(testScheduler))

    @Test
    fun `two readers at once share one build per library change`() = runTest {
        val rows = shared()
        rows.stateIn(backgroundScope, SharingStarted.Eagerly, null)
        rows.stateIn(backgroundScope, SharingStarted.Eagerly, null)
        runCurrent()

        library.value = 2
        runCurrent()

        builds.value shouldBe 2
    }

    @Test
    fun `a reader arriving after every reader left gets a fresh build, not the rows from before`() = runTest {
        val rows = shared()
        rows.first()
        runCurrent()
        library.value = 2

        rows.first() shouldBe 2
    }

    @Test
    fun `a gated reader stops the build while its screen is hidden`() = runTest {
        gatedReader(shared())

        advanceTimeBy(6.seconds)
        runCurrent()

        (builds.value to running.value) shouldBe (1 to 0)
    }

    @Test
    fun `a gated reader coming back on screen builds again`() = runTest {
        val (built, state) = gatedReader(shared())
        advanceTimeBy(6.seconds)
        runCurrent()
        library.value = 2

        backgroundScope.launch { state.collect {} }
        runCurrent()

        built.value shouldBe 2
    }

    // The library models' shape: an eager build under the gate, read by a screen state the gate watches,
    // which nothing collects, so the gate shuts five seconds in.
    private fun TestScope.gatedReader(rows: Flow<Int>): Pair<StateFlow<Int?>, StateFlow<Int?>> {
        val gate = SubscriberGate()
        val built = rows.stateIn(backgroundScope + gate, SharingStarted.Eagerly, null)
        val state = built.stateIn(backgroundScope + gate, gate.whileSubscribed(5.seconds), null)
        return built to state
    }
}
