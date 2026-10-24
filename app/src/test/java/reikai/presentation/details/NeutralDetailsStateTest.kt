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

/** The details adapters' shared state pipeline: seeded without a Loading frame, and stopped with its screen. */
class NeutralDetailsStateTest {

    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    private val source = MutableStateFlow(1)

    private val model = main.track(object : ViewModel() {})

    private fun neutral(value: Int): EntryDetailsScreenState = EntryDetailsScreenState.Failed("state $value")

    // The mapper runs on Dispatchers.Default, off the test clock, so waits run in real time.
    private suspend fun <T> settle(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(5.seconds) { block() } }

    @Test
    fun `the neutral state is seeded with the mapped current value before anyone collects`() {
        model.neutralDetailsState(source, ::neutral).value shouldBe neutral(1)
    }

    @Test
    fun `the mapper stops when the last collector leaves`() = runTest {
        val state = model.neutralDetailsState(source, ::neutral)
        val collector = backgroundScope.launch(Dispatchers.Default) { state.collect {} }
        settle { source.subscriptionCount.first { it == 1 } }

        collector.cancel()

        settle { source.subscriptionCount.first { it == 0 } } shouldBe 0
    }
}
