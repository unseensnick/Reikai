package reikai.presentation.library.hidden

import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.presentation.library.EntryTracks
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.library.model.LibrarySort
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.track.model.Track

/** Hidden entries sort as the library does, the tracker score included, over both content types. */
class HiddenEntriesOrderTest {

    @Test
    fun `hidden rows sorted by tracker score come out in score order`() {
        val manga = row(EntryId.Manga(1L), title = "A")
        val novel = row(EntryId.Novel(1L), title = "B")
        val tracks = EntryTracks(
            manga = mapOf(1L to listOf(track(1L, score = 3.0))),
            novels = mapOf(1L to listOf(track(1L, score = 8.0))),
            trackers = listOf(tracker),
        )

        hiddenRowsInOrder(
            rows = listOf(manga, novel),
            hidden = setOf(manga.entryId, novel.entryId),
            customInfo = emptyMap(),
            sort = LibrarySort(LibrarySort.Type.TrackerMean, LibrarySort.Direction.Descending),
            seed = 0L,
            tracks = tracks,
        ).map { it.entryId } shouldBe listOf(novel.entryId, manga.entryId)
    }

    private val tracker = mockk<Tracker> {
        every { id } returns TRACKER
        every { get10PointScore(any()) } answers { firstArg<Track>().score }
    }

    private fun track(entryId: Long, score: Double) = Track(
        id = entryId,
        mangaId = entryId,
        trackerId = TRACKER,
        remoteId = 0L,
        libraryId = null,
        title = "",
        lastChapterRead = 0.0,
        totalChapters = 0L,
        status = 0L,
        score = score,
        remoteUrl = "",
        startDate = null,
        finishDate = null,
        private = false,
    )

    private fun row(entryId: EntryId, title: String) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = entryId.rawId, title = title),
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = null,
            chapterFetchedAt = null,
            lastRead = null,
        ),
        downloadCount = 0,
        unreadCount = 0,
        isLocal = false,
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        entryId = entryId,
    )

    private companion object {
        const val TRACKER = 1L
    }
}
