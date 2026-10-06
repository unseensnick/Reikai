package reikai.domain.novel.track

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelTrackRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference

class PushNovelUnreadTest {

    private fun chapter(id: Long, novelId: Long) = NovelChapter(
        id = id, novelId = novelId, url = "", name = "", read = true, bookmark = false,
        lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = id, dateFetch = 0L,
        dateUpload = 0L, page = "",
    )

    private val track = NovelTrack(
        id = 5L, novelId = 1L, trackerId = 100L, remoteId = 9L, libraryId = null, title = "t",
        lastChapterRead = 0.0, totalChapters = 0, status = 0, score = 0.0, remoteUrl = "",
        startDate = 0, finishDate = 0, private = false,
    )

    // Novels 1 and 2 are one merge group; only novel 1 carries the track.
    private fun push(sharing: Boolean) = PushNovelUnread(
        getNovelTracks = GetNovelTracks(
            repository = mockk<NovelTrackRepository> {
                coEvery { getTracksByNovelId(1L) } returns listOf(track)
                coEvery { getTracksByNovelId(2L) } returns emptyList()
            },
            mergeManager = mockk<NovelMergeManager> {
                every { relatedIdsChanges() } returns emptyFlow()
                coEvery { relatedIdsList(any()) } returns listOf(1L, 2L)
            },
            preferences = ReikaiLibraryPreferences(
                InMemoryPreferenceStore(sequenceOf(InMemoryPreference("sync_tracker_links_grouped", sharing, true))),
            ),
        ),
        trackerManager = mockk(),
        upsertNovelTrack = mockk(),
    )

    private val unread = listOf(chapter(8, novelId = 1L), chapter(10, novelId = 2L))

    @Test
    fun `an unread across two merged sources reaches their shared tracker once`() = runTest {
        push(sharing = true).tracksFor(unread) shouldBe listOf(track to unread)
    }

    @Test
    fun `with sharing off a track hears only its own source's unread`() = runTest {
        push(sharing = false).tracksFor(unread) shouldBe listOf(track to listOf(unread.first()))
    }
}
