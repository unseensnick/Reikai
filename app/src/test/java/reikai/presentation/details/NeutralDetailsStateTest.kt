package reikai.presentation.details

import androidx.lifecycle.ViewModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import reikai.presentation.MainDispatcherExtension
import kotlin.time.Duration.Companion.seconds

/** The details adapters' shared state pipeline: never mapped where it is asked for, and stopped with its screen. */
class NeutralDetailsStateTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val source = MutableStateFlow(1)

    private val duplicates = MutableStateFlow(Unit)

    private val model = main.track(object : ViewModel() {})

    private fun neutral(value: Int): EntryDetailsScreenState = EntryDetailsScreenState.Failed("state $value")

    private fun mappedOnTheSpot(value: Int): EntryDetailsScreenState = error("mapped where it was asked for")

    // The mapper runs on Dispatchers.Default, off the test clock, so waits run in real time.
    private suspend fun <T> settle(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { block() } }

    /** The mapping walks every chapter row, and asking for the state happens on Main, in composition. */
    @Test
    fun `a first open starts from Loading rather than mapping the model on the spot`() {
        model.neutralDetailsState(source, duplicates) { value, _ -> mappedOnTheSpot(value) }.value shouldBe
            EntryDetailsScreenState.Loading
    }

    /** An adapter is rebuilt on every re-entry to the screen, which must not flash Loading. */
    @Test
    fun `an adapter rebuilt over the same model starts from the last mapped state`() = runTest {
        settle {
            model.neutralDetailsState(source, duplicates) { value, _ -> neutral(value) }.first { it == neutral(1) }
        }

        model.neutralDetailsState(source, duplicates) { value, _ -> mappedOnTheSpot(value) }.value shouldBe neutral(1)
    }

    @Test
    fun `the mapper stops when the last collector leaves`() = runTest {
        val state = model.neutralDetailsState(source, duplicates) { value, _ -> neutral(value) }
        val collector = backgroundScope.launch(Dispatchers.Default) { state.collect {} }
        settle { source.subscriptionCount.first { it == 1 } }

        collector.cancel()

        settle { source.subscriptionCount.first { it == 0 } } shouldBe 0
    }
}
