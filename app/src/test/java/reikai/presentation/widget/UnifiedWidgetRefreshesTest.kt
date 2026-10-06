package reikai.presentation.widget

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The combined widget's refresh driver watches the library only while a combined widget is placed. */
internal class UnifiedWidgetRefreshesTest {

    private val placed = MutableStateFlow(false)
    private var watching = 0
    private val updates = flow {
        emit(UnifiedWidgetUpdates(manga = emptyList(), novel = emptyList()))
        awaitCancellation()
    }
        .onStart { watching++ }
        .onCompletion { watching-- }

    private fun TestScope.collectRefreshes() {
        backgroundScope.launch { unifiedWidgetRefreshes(placed, updates, flowOf(false)).collect() }
        runCurrent()
    }

    @Test
    fun `nothing is watched while no widget is placed`() = runTest {
        collectRefreshes()

        watching shouldBe 0
    }

    @Test
    fun `placing a widget starts the watch`() = runTest {
        collectRefreshes()

        placed.value = true
        runCurrent()

        watching shouldBe 1
    }

    @Test
    fun `removing the last widget stops the watch`() = runTest {
        placed.value = true
        collectRefreshes()

        placed.value = false
        runCurrent()

        watching shouldBe 0
    }
}
