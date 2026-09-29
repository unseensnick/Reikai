package reikai.presentation.recents

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember

/**
 * Whether a list sitting at its very top is taken back there as a new first row arrives. LazyColumn
 * holds on to its first visible item by key, so rows landing above it stay out of view: History
 * opened on Yesterday with today's reads above the fold.
 */
internal fun staysAtTop(firstVisibleIndex: Int, firstVisibleOffset: Int, shownFirstKey: Any?, firstKey: Any?) =
    firstVisibleIndex == 0 && firstVisibleOffset == 0 && shownFirstKey != firstKey

/**
 * Applies [staysAtTop] as each set of rows is composed. A side effect runs before the list measures
 * the new rows, so [state] still says where the old list stood.
 */
@Composable
internal fun KeepListAtTop(state: LazyListState, firstKey: Any?) {
    val shown = remember { ShownFirstKey(firstKey) }
    SideEffect {
        if (staysAtTop(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, shown.key, firstKey)) {
            state.requestScrollToItem(0)
        }
        shown.key = firstKey
    }
}

private class ShownFirstKey(var key: Any?)
