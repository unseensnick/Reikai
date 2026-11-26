package reikai.presentation.library

import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import reikai.domain.entry.EntryId
import reikai.presentation.ComposeTestHost
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/**
 * The real library cell on a Manual category's tile: the drag owns the long press, so the cell has no
 * long click of its own and reports the release as a tap, which must not open the series.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class LibraryItemCellTapTest(private val displayMode: LibraryDisplayMode) {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a long press released on a manual tile does not open the series`() {
        val opened = mutableListOf<LibraryItem>()
        val rows = listOf(row())
        compose.setContent {
            ComposeTestHost {
                val gridState = rememberLazyGridState()
                val manualOrder = rememberManualOrder()
                val reorder = rememberManualOrderGrid(gridState, manualOrder) { rows }
                LazyVerticalGrid(columns = GridCells.Fixed(2), state = gridState) {
                    libraryCells(
                        section = PAGE_SECTION,
                        rows = rows,
                        contentType = "cell",
                        drag = ManualOrderDrag { _, _ -> },
                        manualOrder = manualOrder,
                        reorder = reorder,
                        onLongClick = {},
                        cell = libraryItemCell(displayMode, emptySet(), { opened += it }, null),
                    )
                }
            }
        }

        compose.onNodeWithText(TITLE).performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }
        compose.waitForIdle()

        opened shouldBe emptyList()
    }

    private fun row() = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = 1L, title = TITLE),
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = null,
            chapterFetchedAt = null,
            lastRead = null,
        ),
        downloadCount = 0,
        unreadCount = 0,
        isLocal = false,
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        entryId = EntryId.Manga(1L),
    )

    companion object {
        private const val TITLE = "Alpha"
        private const val LONG_PRESS_MS = 1_000L

        // One per cell composable the modes draw; cover only has no title to press.
        @JvmStatic
        @Parameters(name = "{0}")
        fun modes() = listOf<Any>(
            LibraryDisplayMode.List,
            LibraryDisplayMode.ComfortableGrid,
            LibraryDisplayMode.CompactGrid,
        ).map { arrayOf(it) }
    }
}
