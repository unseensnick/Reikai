package reikai.presentation.widget

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

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
    fun `a flip of Show alone redraws, though no chapter changed`() = runTest {
        val shown = MutableStateFlow(ContentType.ALL)
        val drawn = mutableListOf<UnifiedWidgetDrawn>()
        val updates = shown.map { UnifiedWidgetUpdates(emptyList(), emptyList(), it) }
        backgroundScope.launch { unifiedWidgetRefreshes(flowOf(true), updates, flowOf(false)).toList(drawn) }
        runCurrent()

        shown.value = ContentType.MANGA
        runCurrent()

        drawn.map { it.shown } shouldBe listOf(ContentType.ALL, ContentType.MANGA)
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
