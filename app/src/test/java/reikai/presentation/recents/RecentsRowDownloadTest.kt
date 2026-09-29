package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.merge.ChapterUnit

/**
 * A merged recents row's download state, the one rule both adapters draw it by. Chapter 1 is the copy
 * the row names; chapter 2 is another source's copy of it; chapter 3 is the next chapter.
 */
class RecentsRowDownloadTest {

    private val stitch = listOf(ChapterUnit(1L, 0, 0), ChapterUnit(2L, 0, 1), ChapterUnit(3L, 1, 0))
    private val pooled = listOf(1L, 2L, 3L)

    private fun state(lane: RecentsLane, onDisk: Set<Long>, queued: Download.State? = null): Download.State {
        val copies = recentsRowCopies(1L, stitch, pooled) { it }
        return recentsRowDownloadState(lane, 1L, copies, stitch, { it }, queued) { it in onDisk }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("groupLanes")
    fun `a row that opens in group scope reads as downloaded when another source's copy is`(lane: RecentsLane) {
        state(lane, onDisk = setOf(2L)) shouldBe Download.State.DOWNLOADED
    }

    @Test
    fun `an Updates row reads as downloaded only when its own copy is`() {
        state(updated, onDisk = setOf(2L)) shouldBe Download.State.NOT_DOWNLOADED
    }

    @Test
    fun `another chapter on disk does not count`() {
        state(read, onDisk = setOf(3L)) shouldBe Download.State.NOT_DOWNLOADED
    }

    @Test
    fun `the named chapter's queue entry wins over a copy on disk`() {
        state(read, onDisk = setOf(2L), queued = Download.State.QUEUE) shouldBe Download.State.QUEUE
    }

    companion object {
        private val ref = ChapterRef(EntryId.Manga(1L), 1L)
        private val read = RecentsLane.Read(ref)
        private val updated = RecentsLane.Updated(ref)

        @JvmStatic
        fun groupLanes() = listOf(read, RecentsLane.Added)
    }
}
