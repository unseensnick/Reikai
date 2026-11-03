package reikai.presentation.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import eu.kanade.presentation.more.settings.widget.ShownContentPreferenceWidget
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.category.CategoryContentType
import reikai.domain.library.ContentType
import reikai.presentation.category.CategoryFilterRow
import reikai.presentation.download.EntryDownloadCardList
import reikai.presentation.download.EntryDownloadCardStatus
import reikai.presentation.download.EntryDownloadCardUi
import tachiyomi.domain.category.model.Category

/** The Show setting drawn in Appearance and on the theme step, and the chips it takes away. */
@RunWith(RobolectricTestRunner::class)
class ShownContentUiTest {

    @get:Rule
    val compose = createComposeRule()

    private fun showChips(shown: ContentType) = compose.setContent {
        CompositionLocalProvider(LocalShownContent provides shown) {
            ContentTypeFilterChips(selected = ContentType.ALL, onSelect = {})
        }
    }

    private fun showBadge(shown: ContentType) = compose.setContent {
        CompositionLocalProvider(LocalShownContent provides shown) {
            ContentTypeBadge(ContentType.NOVELS)
        }
    }

    @Test
    fun `the setting offers all three choices and marks the current one`() {
        compose.setContent { ShownContentPreferenceWidget(value = ContentType.MANGA, onItemClick = {}) }

        compose.onNodeWithText("All").assertExists()
        compose.onNodeWithText("Novels").assertExists()
        compose.onNodeWithText("Manga").assertIsOn()
    }

    @Test
    fun `picking a choice reports it`() {
        var picked: ContentType? = null
        compose.setContent { ShownContentPreferenceWidget(value = ContentType.ALL, onItemClick = { picked = it }) }

        compose.onNodeWithText("Novels").performClick()

        picked shouldBe ContentType.NOVELS
    }

    @Test
    fun `the type chips show while both types do`() {
        showChips(ContentType.ALL)

        compose.onNodeWithText("Manga").assertExists()
    }

    @Test
    fun `the type chips leave with a hidden type`() {
        showChips(ContentType.NOVELS)

        compose.onNodeWithText("Novels").assertDoesNotExist()
    }

    @Test
    fun `the Recents category picker leaves out a hidden type's categories`() {
        showCategoryPicker(ContentType.MANGA)

        compose.onNodeWithText("Comic").assertExists()
        compose.onNodeWithText("Book").assertDoesNotExist()
    }

    @Test
    fun `the Recents category picker lists both types while both show`() {
        showCategoryPicker(ContentType.ALL)

        compose.onNodeWithText("Book").assertExists()
    }

    @Test
    fun `a mixed download queue still names its cards under Show`() {
        compose.setContent {
            CompositionLocalProvider(LocalShownContent provides ContentType.MANGA) {
                EntryDownloadCardList(
                    items = listOf(queueCard(ContentType.MANGA, 1L), queueCard(ContentType.NOVELS, 2L)),
                    showTypeBadge = true,
                    onReorder = {},
                    onCancel = {},
                    onOpen = {},
                    contentPadding = PaddingValues(),
                )
            }
        }

        compose.onNodeWithText("Novels").assertExists()
    }

    private fun queueCard(type: ContentType, id: Long) = EntryDownloadCardUi(
        contentType = type,
        seriesId = id,
        sourceName = "Source",
        title = "Series $id",
        downloadedChapters = 0,
        totalChapters = 1,
        status = EntryDownloadCardStatus.QUEUED,
    )

    private fun showCategoryPicker(shown: ContentType) {
        compose.setContent {
            CompositionLocalProvider(LocalShownContent provides shown) {
                Column {
                    CategoryFilterRow(
                        enabled = true,
                        onToggleEnabled = {},
                        categories = listOf(
                            Category(id = 1L, name = "Comic", order = 0L, flags = 0L),
                            Category(
                                id = 2L,
                                name = "Book",
                                order = 1L,
                                flags = 0L,
                                contentType = CategoryContentType.NOVEL,
                            ),
                        ),
                        included = emptySet(),
                        excluded = emptySet(),
                        onConfirm = { _, _ -> },
                        showContentTypeChip = true,
                    )
                }
            }
        }
        compose.onNodeWithText("Edit", substring = true, ignoreCase = true).performClick()
    }

    @Test
    fun `a row's type badge shows while both types do`() {
        showBadge(ContentType.ALL)

        compose.onNodeWithText("Novels").assertExists()
    }

    @Test
    fun `a row's type badge leaves with a hidden type`() {
        showBadge(ContentType.NOVELS)

        compose.onNodeWithText("Novels").assertDoesNotExist()
    }
}
