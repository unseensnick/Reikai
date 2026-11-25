package reikai.domain.merge

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

class ReconcileMergedChaptersTest {

    @Test
    fun `a group whose stitch throws holds up neither its type nor the other type`() = runTest {
        val rebuilt = mutableListOf<Long>()
        val repository = mockk<MergedChapterUnitRepository>(relaxed = true) {
            coEvery { getRankings() } returns emptyMap()
            coEvery { claimPendingGroups(ContentType.MANGA) } returns listOf(1L, 2L)
            coEvery { claimPendingGroups(ContentType.NOVELS) } returns listOf(3L)
            coEvery { isStale(any(), any()) } returns true
            coEvery { replaceGroup(any(), any(), any(), any()) } answers { rebuilt += secondArg<Long>() }
        }

        ReconcileMergedChapters(repository, linkedSetOf(stitcher(ContentType.MANGA), stitcher(ContentType.NOVELS)))
            .await()

        rebuilt shouldBe listOf(2L, 3L)
    }

    /** Throws on group 1, the bad row. */
    private fun stitcher(type: ContentType) = mockk<MergedGroupStitcher> {
        coEvery { contentType } returns type
        coEvery { rankings() } returns emptyMap()
        coEvery { stitch(1L) } throws IllegalStateException("bad chapter row")
        coEvery { stitch(neq(1L)) } returns MergedChapterUnitRepository.GroupStitch(emptyList(), emptyMap())
    }
}
