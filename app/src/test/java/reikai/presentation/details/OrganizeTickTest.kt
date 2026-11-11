package reikai.presentation.details

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.local.CombinableSeries
import reikai.presentation.ComposeTestHost
import reikai.presentation.library.CombineVolumesDialog

/**
 * Also organize files moves the user's files, so every dialog that offers it leaves it unticked, and none
 * offers it where the storage cannot move a file. Both content types share these dialogs.
 */
@RunWith(RobolectricTestRunner::class)
class OrganizeTickTest {

    @get:Rule
    val compose = createComposeRule()

    private var organize: Boolean? = null

    private val volumes = listOf(
        EntryChapterListItem.Volume("Saga", "Vol 1", VolumeLabel.Number(1.0), listOf(1L), 0),
        EntryChapterListItem.Volume("Saga", "Vol 2", VolumeLabel.Number(2.0), listOf(2L), 0),
    )

    private fun split(canOrganize: Boolean) = compose.setContent {
        ComposeTestHost {
            SplitOutDialog("Saga", volumes, canOrganize, {}) { _, _, organize -> this.organize = organize }
        }
    }

    private fun combine(canOrganize: Boolean) = compose.setContent {
        ComposeTestHost {
            CombineVolumesDialog(
                listOf(CombinableSeries(1, "Saga", "Saga"), CombinableSeries(2, "Other", "Other")),
                canOrganize,
                {},
            ) { _, organize -> this.organize = organize }
        }
    }

    private fun reorder() = compose.setContent {
        ComposeTestHost {
            ReorderVolumesDialog(volumes, true, null, {}, { organize = it }, {}, { _, _ -> })
        }
    }

    @Test
    fun `a split organizes the new series' files only when ticked`() {
        split(canOrganize = true)

        compose.onNodeWithText("Vol. 1").performClick()
        compose.onNodeWithText("Split out").performClick()

        organize shouldBe false
    }

    @Test
    fun `a ticked split organizes the new series' files`() {
        split(canOrganize = true)

        compose.onNodeWithText("Vol. 1").performClick()
        compose.onNodeWithText("Also organize files").performClick()
        compose.onNodeWithText("Split out").performClick()

        organize shouldBe true
    }

    @Test
    fun `a split where the storage cannot move files does not offer to organize them`() {
        split(canOrganize = false)

        compose.onAllNodesWithText("Also organize files").assertCountEquals(0)
    }

    @Test
    fun `a combine organizes the files only when ticked`() {
        combine(canOrganize = true)

        compose.onNodeWithText("Combine").performClick()

        organize shouldBe false
    }

    @Test
    fun `a combine where the storage cannot move files does not offer to organize them`() {
        combine(canOrganize = false)

        compose.onAllNodesWithText("Also organize files").assertCountEquals(0)
    }

    @Test
    fun `renaming a volume organizes the files only when ticked`() {
        reorder()

        compose.onAllNodesWithContentDescription("Edit")[0].performClick()
        compose.onNodeWithText("Save").performClick()
        compose.onNodeWithText("Close").performClick()

        organize shouldBe false
    }
}
