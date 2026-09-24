package reikai.presentation.details

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The details mark-read step both content types run. It writes the read across a merged series' copies
 * but tells the trackers about the chapters the user marked: a sibling source numbered its copy of
 * chapter 10 as 11, and pushing the copies set the tracker to 11.
 */
class EntryAutoTrackOnMarkReadTest {

    /** A chapter as the step sees it: an id and the number its own source gave it. */
    private data class Copy(val id: Long, val number: Double)

    private val marked = Copy(id = 1L, number = 10.0)
    private val siblingCopy = Copy(id = 2L, number = 11.0)

    private val written = mutableListOf<Copy>()
    private val pushed = mutableListOf<Double>()

    private val step = EntryAutoTrackOnMarkRead<Copy>(
        context = mockk(relaxed = true),
        snackbarHostState = mockk {
            coEvery { showSnackbar(any(), any(), any(), any()) } returns SnackbarResult.ActionPerformed
        },
        trackerManager = mockk { every { loggedInTrackers() } returns listOf(mockk()) },
        trackPreferences = mockk<TrackPreferences> {
            every { autoUpdateTrackOnMarkRead } returns mockk { every { get() } returns AutoTrackState.ASK }
        },
        expandToGroup = { chapters -> chapters + siblingCopy },
        writeRead = { chapters, _ -> written += chapters },
        chapterNumber = Copy::number,
        refresh = { emptyList() },
        lastReadPerTracker = { listOf(0.0) },
        pushProgress = { _, number -> pushed += number },
    )

    @Test
    fun `the read reaches every copy in the group`() = runTest {
        step.setRead(entryId = 1L, chapters = listOf(marked), read = true)

        written shouldBe listOf(marked, siblingCopy)
    }

    @Test
    fun `the tracker hears the marked chapter's number, not a copy's`() = runTest {
        step.setRead(entryId = 1L, chapters = listOf(marked), read = true)

        pushed shouldBe listOf(10.0)
    }

    @Test
    fun `marking unread tells no tracker`() = runTest {
        step.setRead(entryId = 1L, chapters = listOf(marked), read = false)

        pushed shouldBe emptyList()
    }
}
