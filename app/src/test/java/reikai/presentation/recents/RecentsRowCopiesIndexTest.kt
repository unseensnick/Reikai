package reikai.presentation.recents

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.merge.ChapterCopyRow
import reikai.domain.merge.ChapterUnit

/**
 * A History row's copies, loaded with the read lane so the row and the Downloaded filter (both of which
 * ask the provider's download state) see a merged chapter's other copies. Entry 1 is merged; its row
 * names chapter 10, whose copy 20 is on another source. Entry 2 stands alone, with chapter 40.
 */
class RecentsRowCopiesIndexTest {

    private val merged = EntryId.Manga(1L)
    private val alone = EntryId.Manga(2L)
    private val mergedRow = RecentsItem(merged, 100, RecentsLane.Read(ChapterRef(merged, 10L)), Unit)
    private val aloneRow = RecentsItem(alone, 90, RecentsLane.Read(ChapterRef(alone, 40L)), Unit)
    private val lane = flowOf(RecentsLaneRows(listOf(mergedRow, aloneRow), loaded = true))

    private fun copy(named: Long, id: Long, order: Int) =
        ChapterCopyRow(named, ChapterUnit(id, 0, order), "t", "1", "c$id", null, "/$id")

    // Answers for any id it is asked, so only the lane's own membership check keeps entry 2 out.
    private val query: (List<Long>) -> Flow<Map<Long, List<ChapterCopyRow>>> = { ids ->
        flowOf(ids.associateWith { listOf(copy(it, it, 0), copy(it, it + 10, 1)) })
    }

    private fun stateOf(index: RecentsRowCopiesIndex, chapterId: Long, onDisk: Set<Long>): Download.State {
        val copies = index.copiesOf(chapterId, "t", "1", "c$chapterId", null, "/$chapterId")
        return recentsRowDownloadState(mergedRow.lane, chapterId, copies, null) { it.copy.chapterId in onDisk }
    }

    @Test
    fun `a History row whose only copy on disk is another source's reads as downloaded`() = runTest {
        val index = RecentsRowCopiesIndex()
        index.lane(lane, flowOf(mapOf(merged to 7L)), query).first()

        stateOf(index, 10L, onDisk = setOf(20L)) shouldBe Download.State.DOWNLOADED
    }

    @Test
    fun `a row on an entry in no group answers for its own copy`() = runTest {
        val index = RecentsRowCopiesIndex()
        index.lane(lane, flowOf(mapOf(merged to 7L)), query).first()

        stateOf(index, 40L, onDisk = setOf(50L)) shouldBe Download.State.NOT_DOWNLOADED
    }

    @Test
    fun `with merging off a row answers for its own copy`() = runTest {
        val index = RecentsRowCopiesIndex()
        index.lane(lane, flowOf(emptyMap()), query).first()

        stateOf(index, 10L, onDisk = setOf(20L)) shouldBe Download.State.NOT_DOWNLOADED
    }

    @Test
    fun `copies arriving without a lane change still tell the rows to redraw`() = runTest {
        val index = RecentsRowCopiesIndex()
        val stitched = MutableStateFlow(false)
        var ticks = 0
        backgroundScope.launch { index.changes.collect { ticks++ } }
        backgroundScope.launch {
            index.lane(lane, flowOf(mapOf(merged to 7L))) { ids ->
                stitched.map { done -> if (done) ids.associateWith { listOf(copy(it, it, 0)) } else emptyMap() }
            }.collect {}
        }
        runCurrent()
        stitched.value = true
        runCurrent()

        ticks shouldBe 2
    }
}
