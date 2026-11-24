package reikai.presentation.reader

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** A bar button's row is one TalkBack stop, read with the button's name and whether it shows. */
@RunWith(RobolectricTestRunner::class)
class ReaderBottomButtonsRowTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aButtonRowIsOneCheckboxNamedForItsButton() {
        compose.setContent {
            ComposeTestHost {
                val listState = rememberLazyListState()
                val reorder = rememberReorderableLazyListState(listState) { _, _ -> }
                LazyColumn(state = listState) {
                    item(key = ReaderBottomButton.Share) {
                        ReorderableItem(reorder, ReaderBottomButton.Share) {
                            ButtonRow(ReaderBottomButtonsViewModel.Row(ReaderBottomButton.Share, true), onToggle = {})
                        }
                    }
                }
            }
        }

        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNode(isToggleable() and hasText("Share")).assertIsOn()
    }
}
