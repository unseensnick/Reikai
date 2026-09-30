package eu.kanade.tachiyomi.data.track.novellist

import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.novellist.NovelList.Companion.COMPLETED
import eu.kanade.tachiyomi.data.track.novellist.dto.NLReadingListEntry
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.track.TrackFieldMutations

/** A refresh takes the chapter total from the catalogue, which keeps growing for an ongoing novel. */
class NovelListRefreshTest {

    @Test
    fun `a refresh takes the catalogue's current chapter count as the total`() {
        val track = boundAt(total = 300)
        refreshTrack(track, entry(read = 450), catalogueCount = 520)
        track.total_chapters shouldBe 520L
    }

    @Test
    fun `a catalogue with no count keeps the total it had`() {
        val track = boundAt(total = 300)
        refreshTrack(track, entry(read = 450), catalogueCount = null)
        track.total_chapters shouldBe 300L
    }

    @Test
    fun `marking a refreshed novel Completed never lowers its progress to the bind-time total`() {
        val track = boundAt(total = 300)
        refreshTrack(track, entry(read = 450), catalogueCount = 520)
        TrackFieldMutations.applyStatus(tracker, track, COMPLETED)
        track.last_chapter_read shouldBe 520.0
    }

    private val tracker = mockk<Tracker> { every { getCompletionStatus() } returns COMPLETED }

    private fun boundAt(total: Long) = Track.create(1L).apply { total_chapters = total }

    private fun entry(read: Long) = NLReadingListEntry(status = "IN_PROGRESS", chapterCount = read)
}
