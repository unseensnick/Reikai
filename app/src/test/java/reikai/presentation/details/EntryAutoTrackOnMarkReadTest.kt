package reikai.presentation.details

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import eu.kanade.domain.track.model.AutoTrackState
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.network.HttpException
import eu.kanade.tachiyomi.util.system.toast
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.track.ChapterPushOutcome
import reikai.presentation.track.trackerErrorMessage
import tachiyomi.core.common.i18n.stringResource

/**
 * The details mark-read step both content types run. It writes the read across a merged series' copies
 * but tells the trackers about the chapters the user marked: a sibling source numbered its copy of
 * chapter 10 as 11, and pushing the copies set the tracker to 11. A mark tells the user only what
 * failed, refresh or push, in one toast.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryAutoTrackOnMarkReadTest {

    /** A chapter as the step sees it: an id and the number its own source gave it. */
    private data class Copy(val id: Long, val number: Double)

    private val marked = Copy(id = 1L, number = 10.0)
    private val siblingCopy = Copy(id = 2L, number = 11.0)
    private val anilist = tracker("AniList")
    private val kitsu = tracker("Kitsu")

    private val written = mutableListOf<Copy>()
    private val pushed = mutableListOf<Double>()
    private val toasts = mutableListOf<String?>()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        mockkStatic(TOAST, LOCALIZE, TRACKER_ERROR)
        every { any<Context>().toast(any<String>(), any(), any()) } answers {
            toasts += secondArg<String?>()
            mockk(relaxed = true)
        }
        every { any<Context>().stringResource(any(), *anyVararg()) } returns "localized"
        every { any<Context>().trackerErrorMessage(any(), any()) } answers { "${secondArg<Tracker>().name} failed" }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(TOAST, LOCALIZE, TRACKER_ERROR)
        Dispatchers.resetMain()
    }

    @Test
    fun `the read reaches every copy in the group`() = runTest {
        step().setRead(entryId = 1L, chapters = listOf(marked), read = true)

        written shouldBe listOf(marked, siblingCopy)
    }

    @Test
    fun `the tracker hears the marked chapter's number, not a copy's`() = runTest {
        step().setRead(entryId = 1L, chapters = listOf(marked), read = true)

        pushed shouldBe listOf(10.0)
    }

    @Test
    fun `marking unread tells no tracker`() = runTest {
        step().setRead(entryId = 1L, chapters = listOf(marked), read = false)

        pushed shouldBe emptyList()
    }

    @Test
    fun `a push every tracker took shows nothing`() = runTest {
        step(state = AutoTrackState.ALWAYS).setRead(entryId = 1L, chapters = listOf(marked), read = true)

        toasts shouldBe emptyList()
    }

    @Test
    fun `a push confirmed from the prompt that lands shows nothing`() = runTest {
        step(state = AutoTrackState.ASK).setRead(entryId = 1L, chapters = listOf(marked), read = true)

        toasts shouldBe emptyList()
    }

    @Test
    fun `a tracker failing its refresh and its push is told once`() = runTest {
        step(
            refreshFailed = listOf(anilist to RATE_LIMITED),
            pushFailed = listOf(anilist to RATE_LIMITED),
        ).setRead(entryId = 1L, chapters = listOf(marked), read = true)

        toasts shouldBe listOf("AniList failed")
    }

    @Test
    fun `a refresh failure and another tracker's push failure share one toast`() = runTest {
        step(
            refreshFailed = listOf(anilist to RATE_LIMITED),
            pushFailed = listOf(kitsu to RATE_LIMITED),
        ).setRead(entryId = 1L, chapters = listOf(marked), read = true)

        toasts shouldBe listOf("AniList failed\nKitsu failed")
    }

    @Test
    fun `a refresh failure is told even when nothing is left to push`() = runTest {
        step(refreshFailed = listOf(anilist to RATE_LIMITED), lastRead = 10.0)
            .setRead(entryId = 1L, chapters = listOf(marked), read = true)

        toasts shouldBe listOf("AniList failed")
    }

    @Test
    fun `a refresh failure is told when the prompt is dismissed`() = runTest {
        step(refreshFailed = listOf(anilist to RATE_LIMITED), prompt = SnackbarResult.Dismissed)
            .setRead(entryId = 1L, chapters = listOf(marked), read = true)

        toasts shouldBe listOf("AniList failed")
    }

    private fun step(
        state: AutoTrackState = AutoTrackState.ASK,
        refreshFailed: List<Pair<Tracker, Throwable>> = emptyList(),
        pushFailed: List<Pair<Tracker, Throwable>> = emptyList(),
        lastRead: Double = 0.0,
        prompt: SnackbarResult = SnackbarResult.ActionPerformed,
    ) = EntryAutoTrackOnMarkRead<Copy>(
        context = mockk(relaxed = true),
        snackbarHostState = mockk<SnackbarHostState> {
            coEvery { showSnackbar(any(), any(), any(), any()) } returns prompt
        },
        trackerManager = mockk { every { loggedInTrackers() } returns listOf(mockk()) },
        trackPreferences = mockk<TrackPreferences> {
            every { autoUpdateTrackOnMarkRead } returns mockk { every { get() } returns state }
        },
        expandToGroup = { chapters -> chapters + siblingCopy },
        writeRead = { chapters, _ -> written += chapters },
        chapterNumber = Copy::number,
        refresh = { refreshFailed },
        lastReadPerTracker = { listOf(lastRead) },
        pushProgress = { _, number ->
            pushed += number
            ChapterPushOutcome(failed = pushFailed)
        },
    )

    private fun tracker(name: String) = mockk<Tracker> {
        every { id } returns name.length.toLong()
        every { this@mockk.name } returns name
    }

    private companion object {
        const val TOAST = "eu.kanade.tachiyomi.util.system.ToastExtensionsKt"
        const val LOCALIZE = "tachiyomi.core.common.i18n.LocalizeKt"
        const val TRACKER_ERROR = "reikai.presentation.track.TrackerErrorMessageKt"
        val RATE_LIMITED = HttpException(429)
    }
}
