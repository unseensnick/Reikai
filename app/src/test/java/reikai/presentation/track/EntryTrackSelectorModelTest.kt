package reikai.presentation.track

import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.track.EntryTrackPort
import reikai.domain.track.EntryTrackPorts
import reikai.domain.track.TrackFieldMutations
import reikai.domain.track.TrackWriter
import tachiyomi.domain.track.model.Track
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/** The track sheet's pickers, whose routes carry only the tracker and the entry, for both content types. */
@OptIn(ExperimentalCoroutinesApi::class)
class EntryTrackSelectorModelTest {

    private val written = mutableListOf<DbTrack>()
    private val stored = MutableStateFlow(listOf(track(lastChapterRead = 5.0)))
    private val tracker = mockk<BaseTracker>(relaxed = true) { every { id } returns TRACKER_ID }

    private val fakeWriter = object : TrackWriter {
        override suspend fun setRemoteStatus(tracker: Tracker, track: DbTrack, status: Long) {
            written += track
        }
        override suspend fun setRemoteLastChapterRead(tracker: Tracker, track: DbTrack, chapterNumber: Int) {
            TrackFieldMutations.applyLastChapterRead(tracker, track, chapterNumber)
            written += track
        }
        override suspend fun setRemoteScore(tracker: Tracker, track: DbTrack, scoreString: String) = Unit
        override suspend fun setRemoteStartDate(tracker: Tracker, track: DbTrack, epochMillis: Long) = Unit
        override suspend fun setRemoteFinishDate(tracker: Tracker, track: DbTrack, epochMillis: Long) = Unit
        override suspend fun setRemotePrivate(tracker: Tracker, track: DbTrack, private: Boolean) = Unit
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @ParameterizedTest
    @MethodSource("entries")
    fun `an edit writes the binding as stored now, not as the page opened on it`(entry: EntryId) = runTest {
        val model = EntryTrackStatusSelectorRoute.Model(TRACKER_ID, entry, appScope(), trackerManager(), ports())
        stored.value = listOf(track(lastChapterRead = 8.0))

        model.setStatus()

        written.single().last_chapter_read shouldBe 8.0
    }

    /** A binding removed elsewhere, or before a restored sheet reopened, leaves the page nothing to edit. */
    @ParameterizedTest
    @MethodSource("entries")
    fun `a picker opened on a binding that is gone says so`(entry: EntryId) = runTest {
        stored.value = emptyList()

        val model = EntryTrackStatusSelectorRoute.Model(TRACKER_ID, entry, appScope(), trackerManager(), ports())

        model.state.value shouldBe TrackPage.Gone
    }

    @Test
    fun `the remove page holds its binding as loading until the read lands`() = runTest {
        appScope()

        val model = EntryTrackerRemoveRoute.Model(
            EntryId.Manga(1L),
            TRACKER_ID,
            mockk(),
            trackerManager(),
            ports(tracks = MutableSharedFlow()),
        )

        model.track.value shouldBe TrackPage.Loading
    }

    @Test
    fun `confirming the chapter picker unmoved keeps fractional progress`() = runTest {
        stored.value = listOf(track(lastChapterRead = 12.5))
        val model = chapterModel()

        model.setChapter()

        written shouldBe emptyList()
    }

    @Test
    fun `a moved chapter picker writes the picked chapter`() = runTest {
        stored.value = listOf(track(lastChapterRead = 12.5))
        val model = chapterModel()
        model.setSelection(13)

        model.setChapter()

        written.single().last_chapter_read shouldBe 13.0
    }

    private fun TestScope.chapterModel() =
        EntryTrackChapterSelectorRoute.Model(TRACKER_ID, EntryId.Manga(1L), appScope(), trackerManager(), ports())

    private fun TestScope.appScope(): CoroutineScope {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        return CoroutineScope(dispatcher)
    }

    private fun trackerManager() = mockk<eu.kanade.tachiyomi.data.track.TrackerManager> {
        every { get(TRACKER_ID) } returns tracker
    }

    private fun ports(tracks: Flow<List<Track>> = stored): EntryTrackPorts {
        val port = mockk<EntryTrackPort> {
            every { tracks() } returns tracks
            every { writer } returns fakeWriter
        }
        return mockk { every { of(any()) } returns port }
    }

    companion object {
        private const val TRACKER_ID = 5L

        @JvmStatic
        fun entries() = listOf(EntryId.Manga(1L), EntryId.Novel(1L))

        private fun track(lastChapterRead: Double) = Track(
            id = 1L,
            mangaId = 1L,
            trackerId = TRACKER_ID,
            remoteId = 7L,
            libraryId = null,
            title = "entry",
            lastChapterRead = lastChapterRead,
            totalChapters = 40L,
            status = 1L,
            score = 0.0,
            remoteUrl = "",
            startDate = null,
            finishDate = null,
            private = false,
        )
    }
}
