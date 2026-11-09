package reikai.presentation.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.entry.EntryId
import reikai.presentation.ComposeTestHost
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** The Manual drag's press rules on a real grid: a drag reorders, a still long press selects, a tap opens. */
@RunWith(RobolectricTestRunner::class)
class ManualOrderTest {

    @get:Rule
    val compose = createComposeRule()

    private val rows = listOf(row(1, "A"), row(2, "B"), row(3, "C"))
    private val taps = mutableListOf<String>()
    private val longPresses = mutableListOf<String>()
    private var dropped: List<String>? = null

    @OptIn(ExperimentalFoundationApi::class)
    private fun show(draggable: Boolean = true) {
        compose.setContent {
            ComposeTestHost {
                val gridState = rememberLazyGridState()
                val manualOrder = rememberManualOrder()
                val reorder = rememberManualOrderGrid(gridState, manualOrder) { rows }
                LazyVerticalGrid(columns = GridCells.Fixed(3), state = gridState) {
                    libraryCells(
                        section = PAGE_SECTION,
                        rows = rows,
                        contentType = "cell",
                        drag = if (draggable) ManualOrderDrag { order, _ -> dropped = order.map(::titleOf) } else null,
                        manualOrder = manualOrder,
                        reorder = reorder,
                        onLongClick = { longPresses += titleOf(it) },
                    ) { item, longClick, tapAllowed ->
                        Box(
                            Modifier
                                .size(100.dp)
                                .combinedClickable(
                                    onClick = { if (tapAllowed()) taps += titleOf(item) },
                                    onLongClick = longClick,
                                ),
                        ) { Text(titleOf(item)) }
                    }
                }
            }
        }
    }

    @Test
    fun `a long press released where it started selects`() {
        show()

        compose.onNodeWithText("A").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }

        longPresses shouldBe listOf("A")
    }

    /** The release of a long press also reports a tap, which would open the series behind the selection. */
    @Test
    fun `a long press released where it started does not open the series`() {
        show()

        compose.onNodeWithText("A").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }

        taps shouldBe emptyList()
    }

    @Test
    fun `a long press moved onto another tile reorders the section`() {
        show()
        val step = compose.onNodeWithText("C").fetchSemanticsNode().boundsInRoot.center -
            compose.onNodeWithText("A").fetchSemanticsNode().boundsInRoot.center

        compose.onNodeWithText("A").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            repeat(STEPS) { moveBy(Offset(step.x / STEPS, step.y / STEPS)) }
            up()
        }
        compose.waitForIdle()

        dropped shouldBe listOf("B", "C", "A")
    }

    /** A drag that went somewhere is never a select, even with no swap, as a release in the gesture strip ends one. */
    @Test
    fun `a long press moved away and released without a swap selects nothing`() {
        show()

        compose.onNodeWithText("A").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            repeat(STEPS) { moveBy(Offset(0f, height * 3f / STEPS)) }
            up()
        }
        compose.waitForIdle()

        longPresses shouldBe emptyList()
    }

    @Test
    fun `a tap after a long press opens the series`() {
        show()
        compose.onNodeWithText("A").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }

        compose.onNodeWithText("B").performTouchInput { click(center) }

        taps shouldBe listOf("B")
    }

    @Test
    fun `a tile outside a manual category keeps its own long press`() {
        show(draggable = false)

        compose.onNodeWithText("A").performTouchInput {
            down(center)
            advanceEventTime(LONG_PRESS_MS)
            up()
        }

        longPresses shouldBe listOf("A")
    }

    private fun titleOf(item: LibraryItem) = item.libraryManga.manga.title

    private fun row(id: Long, title: String) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = id, title = title),
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
        entryId = EntryId.Manga(id),
    )

    private companion object {
        const val LONG_PRESS_MS = 1_000L
        const val STEPS = 20
    }
}
