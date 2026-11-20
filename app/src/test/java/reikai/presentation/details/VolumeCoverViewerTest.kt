package reikai.presentation.details

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

/** A picked volume heads the page with its own cover, so the cover tapped there opens that cover, not the series'. */
@RunWith(RobolectricTestRunner::class)
class VolumeCoverViewerTest {

    @get:Rule
    val compose = createComposeRule()

    private val vol1 = ChapterVolume("Saga", "Vol 1", VolumeLabel.Number(1.0), "Vol 1 - ")
    private val vol2 = ChapterVolume("Saga", "Vol 2", VolumeLabel.Number(2.0), "Vol 2 - ")

    private val picks = object : VolumePickRepository {
        override fun picks(type: ContentType) = MutableStateFlow(mapOf(100L to "Vol 2"))
        override suspend fun set(type: ContentType, entryId: Long, volumeKey: String?) = Unit
        override suspend fun loadedPicks(type: ContentType) = mapOf(100L to "Vol 2")
        override suspend fun restore(type: ContentType, series: String, key: String) = Unit
    }

    private val files = object : VolumeFiles {
        override suspend fun cover(type: ContentType, series: String, key: String): Any? = "$key.jpg"
        override suspend fun description(type: ContentType, series: String, key: String): String? = null
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
    fun `the cover tapped on a picked volume opens that volume's cover`() {
        compose.setContent { ComposeTestHost { DetailsContentHost(behavior).Content() } }

        compose.onNodeWithContentDescription("Cover").performClick()

        verify { behavior.showCoverDialog(match { it?.key == "Vol 2" }) }
    }
}
