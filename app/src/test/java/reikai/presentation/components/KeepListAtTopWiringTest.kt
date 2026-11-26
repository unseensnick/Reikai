package reikai.presentation.components

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tachiyomi.presentation.core.components.FastScrollLazyColumn

/**
 * [KeepListAtTop] in a real list, where LazyColumn would otherwise hold on to the old first row by key.
 * The rows reach the list as a plain value, as on every caller: a lazy content lambda that read a state
 * object itself would let the list measure the new rows before the side effect runs.
 */
@RunWith(RobolectricTestRunner::class)
class KeepListAtTopWiringTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a list at its top shows a new first row that lands above it`() {
        var rows by mutableStateOf(listOf("yesterday") + List(30) { "older $it" })
        lateinit var state: LazyListState
        compose.setContent {
            state = rememberLazyListState()
            Feed(state, rows)
        }

        compose.runOnIdle { rows = listOf("today") + rows }
        compose.waitForIdle()

        compose.runOnIdle { state.firstVisibleItemIndex } shouldBe 0
    }

    @Composable
    private fun Feed(state: LazyListState, rows: List<String>) {
        KeepListAtTop(state, rows.first())
        FastScrollLazyColumn(state = state, modifier = Modifier.height(200.dp)) {
            items(rows, key = { it }) { BasicText(it, Modifier.animateItem().height(50.dp)) }
        }
    }
}
