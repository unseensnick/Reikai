package reikai.presentation.details

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.VolumePickRepository
import reikai.domain.library.ContentType
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.DownloadProgress

/** A volume picked after another never heads the page with the last one's files while its own loads. */
@RunWith(RobolectricTestRunner::class)
class VolumeSwitchHeaderTest {

    @get:Rule
    val compose = createComposeRule()

    private val vol1 = ChapterVolume("Saga", "Vol 1", VolumeLabel.Number(1.0), "Vol 1 - ")
    private val vol2 = ChapterVolume("Saga", "Vol 2", VolumeLabel.Number(2.0), "Vol 2 - ")
    private val picked = MutableStateFlow(mapOf(100L to "Vol 1"))

    private val picks = object : VolumePickRepository {
        override fun picks(type: ContentType) = picked
        override suspend fun set(type: ContentType, entryId: Long, volumeKey: String?) = Unit
        override suspend fun loadedPicks(type: ContentType) = picked.value
        override suspend fun restore(type: ContentType, series: String, key: String) = Unit
    }

    // Vol 2's files never finish reading, so the page stays in the window between the pick and its load.
    private val files = object : VolumeFiles {
        override suspend fun cover(type: ContentType, series: String, key: String): Any? = null
        override suspend fun description(type: ContentType, series: String, key: String): String? =
            if (key == "Vol 1") "First volume blurb" else awaitCancellation()
    }

    private val state = loadedDetails(
        items = listOf(
            detailsChapter(1L, "Vol 1 - Ch 1", 1.0).copy(volume = vol1),
            detailsChapter(2L, "Vol 2 - Ch 2", 2.0).copy(volume = vol2),
        ),
    ).let { it.copy(chapters = it.chapters.copy(volumes = listOf(vol1, vol2))) }

    private val behavior = mockk<EntryDetailsBehavior>(relaxed = true).also { fake ->
        every { fake.downloadProgress(any()) } returns DownloadProgress.Unsupported
        every { fake.duplicateMarking } returns noDuplicateMarking()
        fake.servesThroughVolumeViews(
            MutableStateFlow(state),
            VolumePicking(
                picks,
                NoLocalVolumes,
                files,
                mockk(relaxed = true),
                mockk(relaxed = true),
                CoroutineScope(Dispatchers.Unconfined),
            ),
        )
    }

    @Test
    fun `picking another volume drops the last volume's description while its own loads`() {
        compose.setContent { ComposeTestHost { DetailsContentHost(behavior).Content() } }
        compose.onNodeWithText("First volume blurb", substring = true).assertExists()

        picked.value = mapOf(100L to "Vol 2")
        compose.waitForIdle()

        compose.onNodeWithText("First volume blurb", substring = true).assertDoesNotExist()
    }
}
