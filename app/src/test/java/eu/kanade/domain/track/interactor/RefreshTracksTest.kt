package eu.kanade.domain.track.interactor

import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.manga.MangaMergeManager
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/** A refresh opened from one source of a merged series, where the group's tracker row sits on another. */
class RefreshTracksTest {

    private val marked = mutableListOf<ChapterUpdate>()

    private val server = mockk<BaseTracker>(relaxed = true, moreInterfaces = arrayOf(EnhancedTracker::class)) {
        every { id } returns SERVER_ID
        every { isLoggedIn } returns true
        coEvery { refresh(any()) } answers { firstArg<DbTrack>().apply { last_chapter_read = 3.0 } }
    }

    private val getTracksInGroup = GetTracksInGroup(
        preferences = mockk<ReikaiLibraryPreferences> {
            every { syncTrackerLinksGrouped } returns mockk<Preference<Boolean>> { every { get() } returns true }
        },
        getTracks = mockk<GetTracks> {
            coEvery { await(VIEWED) } returns emptyList()
            coEvery { await(SERVER_COPY) } returns listOf(serverRow())
        },
        mergeManager = mockk<MangaMergeManager> {
            every { relatedIdsChanges() } returns flowOf(Unit)
            coEvery { relatedIdsList(any()) } returns listOf(VIEWED, SERVER_COPY)
        },
    )

    private val refreshTracks = RefreshTracks(
        getTracks = getTracksInGroup,
        trackerManager = mockk<TrackerManager> { every { get(SERVER_ID) } returns server },
        upsertTrack = mockk(relaxed = true),
        syncChapterProgressWithTrack = SyncChapterProgressWithTrack(
            updateChapter = mockk<UpdateChapter> {
                coEvery { awaitAll(any()) } answers { marked += firstArg<List<ChapterUpdate>>() }
            },
            upsertTrack = mockk(relaxed = true),
            getChaptersByMangaId = mockk<GetChaptersByMangaId> {
                coEvery { await(any()) } answers { chaptersOf(firstArg()) }
            },
        ),
    )

    @Test
    fun `a server's progress marks the chapters of the source it tracks`() = runTest {
        refreshTracks.await(VIEWED)

        marked.map { it.id } shouldContainExactlyInAnyOrder chaptersOf(SERVER_COPY).map { it.id }
    }

    /** Chapters 1 to 3, each with an id that names its manga. */
    private fun chaptersOf(mangaId: Long) = listOf(1.0, 2.0, 3.0).map { number ->
        Chapter.create().copy(id = mangaId * 100 + number.toLong(), mangaId = mangaId, chapterNumber = number)
    }

    private fun serverRow() = Track(
        id = 5L,
        mangaId = SERVER_COPY,
        trackerId = SERVER_ID,
        remoteId = 9L,
        libraryId = null,
        title = "t",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = 0.0,
        remoteUrl = "",
        startDate = 0L,
        finishDate = 0L,
        private = false,
    )

    private companion object {
        const val VIEWED = 1L
        const val SERVER_COPY = 2L
        const val SERVER_ID = 6L
    }
}
