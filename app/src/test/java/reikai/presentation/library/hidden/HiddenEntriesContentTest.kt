package reikai.presentation.library.hidden

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.presentation.ComposeTestHost
import tachiyomi.domain.library.model.LibraryDisplayMode
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** The Hidden entries screen draws the hidden series of both types and puts the selected ones back. */
@RunWith(RobolectricTestRunner::class)
class HiddenEntriesContentTest {

    @get:Rule
    val compose = createComposeRule()

    private var unhidden = 0
    private var opened: EntryId? = null

    private fun row(id: Long, title: String, entryId: EntryId) = LibraryItem(
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
        entryId = entryId,
    )

    private val rows = listOf(row(1, "Hidden manga", EntryId.Manga(1)), row(1, "Hidden novel", EntryId.Novel(1)))

    private fun show(state: HiddenEntriesState) = compose.setContent {
        ComposeTestHost {
            HiddenEntriesContent(
                state = state,
                displayMode = LibraryDisplayMode.List,
                getColumnsForOrientation = { error("a list reads no columns") },
                navigateUp = {},
                onSelectContentType = {},
                onClick = { opened = it.entryId },
                onLongClick = {},
                onSelectAll = {},
                onClearSelection = {},
                onUnhide = { unhidden++ },
            )
        }
    }

    @Test
    fun `a hidden novel is drawn beside a hidden manga`() {
        show(HiddenEntriesState(rows, ContentType.ALL))

        compose.onNodeWithText("Hidden novel").assertExists()
    }

    @Test
    fun `tapping a series opens it`() {
        show(HiddenEntriesState(rows, ContentType.ALL))

        compose.onNodeWithText("Hidden novel").performClick()

        opened shouldBe EntryId.Novel(1)
    }

    @Test
    fun `Unhide puts the selection back`() {
        show(HiddenEntriesState(rows, ContentType.ALL, setOf(EntryId.Manga(1))))

        compose.onNodeWithContentDescription("Unhide").performClick()

        unhidden shouldBe 1
    }

    @Test
    fun `nothing hidden says so`() {
        show(HiddenEntriesState(emptyList(), ContentType.ALL))

        compose.onNodeWithText("No hidden entries").assertExists()
    }
}
