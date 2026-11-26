package reikai.presentation.details

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.data.local.CombineLocalVolumes
import reikai.domain.chapter.NoLocalVolumes
import reikai.domain.chapter.VolumePickRepository
import reikai.domain.library.StoredType
import reikai.presentation.ComposeTestHost
import reikai.presentation.components.DownloadProgress

/** The details page offers to forget an unfinished organize only while one waits, and lists again after Forget. */
@RunWith(RobolectricTestRunner::class)
class UnfinishedOrganizeWiringTest {

    @get:Rule
    val compose = createComposeRule()

    private val picks = object : VolumePickRepository {
        override fun picks(type: StoredType) = MutableStateFlow(emptyMap<Long, String>())
        override suspend fun set(type: StoredType, entryId: Long, volumeKey: String?) = Unit
        override suspend fun loadedPicks(type: StoredType) = emptyMap<Long, String>()
        override suspend fun restore(type: StoredType, series: String, key: String) = Unit
    }

    private val files = object : VolumeFiles {
        override suspend fun cover(type: StoredType, series: String, key: String): Any? = null
        override suspend fun description(type: StoredType, series: String, key: String): String? = null
    }

    private fun behaviorOver(combine: CombineLocalVolumes) = mockk<EntryDetailsBehavior>(relaxed = true).also { fake ->
        every { fake.downloadProgress(any()) } returns DownloadProgress.Unsupported
        every { fake.duplicateMarking } returns noDuplicateMarking()
        val local = loadedDetails().let {
            it.copy(details = it.details.copy(header = it.details.header.copy(sourceState = EntrySourceState.Local)))
        }
        fake.servesThroughVolumeViews(
            MutableStateFlow(local),
            VolumePicking(
                picks,
                NoLocalVolumes,
                files,
                combine,
                mockk(relaxed = true),
                CoroutineScope(Dispatchers.Unconfined),
            ),
        )
    }

    private fun combineWaiting(waits: Boolean) = mockk<CombineLocalVolumes>(relaxed = true).also {
        coEvery { it.series(any(), any()) } returns listOf(mockk(relaxed = true))
        coEvery { it.waitsForRepair(any(), any()) } returns waits
    }

    @Test
    fun `Forget on the banner forgets the organize and then lists the series again`() {
        val combine = combineWaiting(true)
        val behavior = behaviorOver(combine)
        compose.setContent { ComposeTestHost { DetailsContentHost(behavior).Content() } }

        compose.onNodeWithText(BANNER).performClick()
        compose.onNodeWithText("Forget").performClick()
        compose.waitForIdle()

        coVerifyOrder {
            combine.forgetUnfinished(StoredType.MANGA, any())
            behavior.refresh()
        }
    }

    @Test
    fun `no banner shows on a local series nothing holds`() {
        val behavior = behaviorOver(combineWaiting(false))
        compose.setContent { ComposeTestHost { DetailsContentHost(behavior).Content() } }
        compose.waitForIdle()

        compose.onNodeWithText(BANNER).assertDoesNotExist()
    }

    private companion object {
        const val BANNER = "An unfinished organize holds this series"
    }
}
