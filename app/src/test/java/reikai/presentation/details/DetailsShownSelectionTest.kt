package reikai.presentation.details

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.VolumePickRepository
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.DownloadTargets
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.DownloadProgress
import reikai.presentation.components.UndatedChapterDate
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The details list's own filters narrow the rows below the model's list, so the selection verbs must
 * act on the rows on screen. Drawn over a behaviour that selects exactly what it is handed, as both
 * models do: the toolbar's count is then what the screen passed.
 */
@RunWith(RobolectricTestRunner::class)
class DetailsShownSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    private val state = MutableStateFlow(loaded())

    private val behavior = copiesBehavior()

    private fun copiesBehavior() = mockk<EntryDetailsBehavior>(relaxed = true).also { fake ->
        fake.servesThroughVolumeViews(state, noVolumePicking())
        every { fake.downloadProgress(any()) } returns DownloadProgress.Unsupported
        every { fake.duplicateMarking } returns noDuplicateMarking()
        every { fake.toggleSelection(any(), any(), any()) } answers {
            select(setOf(firstArg<Long>()))
        }
        every { fake.selectAll(any()) } answers { select(firstArg<List<Long>>().toSet()) }
    }

    @Test
    fun `select all under Only copies selects the copies alone`() {
        compose.setContent { ComposeTestHost { DetailsContentHost(behavior).Content() } }

        compose.onNodeWithText("Show only these", substring = true).performClick()
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText("Copy one"))
        compose.onNodeWithText("Copy one").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Select all").performClick()

        compose.onNodeWithText(COPIES.size.toString()).assertExists()
    }

    /** A recreated process builds a new model and adapter, with only the page's saved state to go on. */
    @Test
    fun `Only copies is still on after the process is recreated`() {
        var host = DetailsContentHost(behavior)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ComposeTestHost { host.Content() } }
        compose.onNodeWithText("Show only these", substring = true).performClick()
        compose.waitForIdle()

        val recreated = copiesBehavior()
        host = DetailsContentHost(recreated)
        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        (recreated.state.value as EntryDetailsScreenState.Loaded).volumeViews.onlyCopies shouldBe true
    }

    @Test
    fun `select all on a picked volume selects that volume's rows alone`() {
        compose.setContent { ComposeTestHost { DetailsContentHost(inVolumes).Content() } }

        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Ch 3"))
        compose.onNodeWithText("Ch 3").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Select all").performClick()

        compose.onNodeWithText("2").assertExists()
    }

    @Test
    fun `select inverse on a picked volume flips that volume's rows alone`() {
        compose.setContent { ComposeTestHost { DetailsContentHost(inVolumes).Content() } }

        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Ch 3"))
        compose.onNodeWithText("Ch 3").performTouchInput { longClick() }
        compose.onNodeWithContentDescription("Select inverse").performClick()

        compose.onNodeWithText("1").assertExists()
    }

    private fun select(ids: Set<Long>) = state.update { it.copy(selection = it.selection + ids) }

    // A series in two volumes with the second picked, so the screen shows two of its four rows.
    private val volumeState = MutableStateFlow(
        loadedDetails(
            items = listOf(
                detailsChapter(1L, "Vol 1 - Ch 1", 1.0).copy(volume = VOL_1),
                detailsChapter(2L, "Vol 1 - Ch 2", 2.0).copy(volume = VOL_1),
                detailsChapter(3L, "Vol 2 - Ch 3", 3.0).copy(volume = VOL_2),
                detailsChapter(4L, "Vol 2 - Ch 4", 4.0).copy(volume = VOL_2),
            ),
        ).let { it.copy(chapters = it.chapters.copy(volumes = listOf(VOL_1, VOL_2))) },
    )

    private val inVolumes = mockk<EntryDetailsBehavior>(relaxed = true).also { fake ->
        every { fake.downloadProgress(any()) } returns DownloadProgress.Unsupported
        every { fake.duplicateMarking } returns noDuplicateMarking()
        val picking = VolumePicking(
            object : VolumePickRepository {
                override fun picks(type: ContentType) = MutableStateFlow(mapOf(100L to VOL_2.key))
                override suspend fun set(type: ContentType, entryId: Long, volumeKey: String?) = Unit
                override suspend fun loadedPicks(type: ContentType) = mapOf(100L to VOL_2.key)
                override suspend fun restore(type: ContentType, series: String, key: String) = Unit
            },
            NoLocalVolumes,
            object : VolumeFiles {
                override suspend fun cover(type: ContentType, series: String, key: String): Any? = null
                override suspend fun description(type: ContentType, series: String, key: String): String? = null
            },
            mockk(relaxed = true),
            mockk(relaxed = true),
            CoroutineScope(Dispatchers.Unconfined),
        )
        fake.servesThroughVolumeViews(volumeState, picking)
        every { fake.toggleSelection(any(), any(), any()) } answers {
            volumeState.update { it.copy(selection = it.selection + firstArg<Long>()) }
        }
        every { fake.selectAll(any()) } answers {
            volumeState.update { it.copy(selection = it.selection + firstArg<List<Long>>()) }
        }
        every { fake.invertSelection(any()) } answers {
            volumeState.update { it.copy(selection = firstArg<List<Long>>().toSet() - it.selection) }
        }
    }

    private companion object {
        val COPIES = listOf(1L, 2L)
        val VOL_1 = ChapterVolume("Saga", "Vol 1", VolumeLabel.Number(1.0), "Vol 1 - ")
        val VOL_2 = ChapterVolume("Saga", "Vol 2", VolumeLabel.Number(2.0), "Vol 2 - ")

        fun loaded() = loadedDetails(
            items = listOf(
                detailsChapter(1L, "Copy one", 1.0),
                detailsChapter(2L, "Copy two", 1.0),
                detailsChapter(3L, "Another", 2.0),
                detailsChapter(4L, "And another", 3.0),
            ),
            duplicates = DuplicateMarks(copies = COPIES.associateWith { COPIES }, marks = emptyMap()),
        )
    }
}
