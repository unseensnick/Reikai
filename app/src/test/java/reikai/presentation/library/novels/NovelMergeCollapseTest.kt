package reikai.presentation.library.novels

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel

class NovelMergeCollapseTest {

    private fun libNovel(
        id: Long,
        title: String,
        author: String? = null,
        chapters: Long = 1,
        downloads: Long = 0,
        dateAdded: Long = 0,
        lastReadAt: Long? = null,
    ) = LibraryNovel(
        novel = Novel.create().copy(
            id = id, title = title, author = author, favorite = true, dateAdded = dateAdded, lastReadAt = lastReadAt,
        ),
        categories = emptyList(),
        totalChapters = chapters,
        readCount = 0,
        bookmarkCount = 0,
        downloadCount = downloads,
        latestUpload = 0,
        chapterFetchedAt = 0,
    )

    private fun collapse(
        library: List<LibraryNovel>,
        merges: Set<String> = emptySet(),
        unmerges: Set<String> = emptySet(),
        autoMergeSameTitle: Boolean = true,
        requireAuthor: Boolean = true,
    ) = NovelMergeCollapse.collapse(library, merges, unmerges, autoMergeSameTitle, requireAuthor)

    @Test
    fun `a lone novel is its own single-member group`() {
        val result = collapse(listOf(libNovel(1, "A")))
        result.size shouldBe 1
        result.first().memberIds shouldContainExactlyInAnyOrder listOf(1L)
    }

    @Test
    fun `a manual merge collapses to the most-chapters representative`() {
        val result = collapse(
            listOf(libNovel(1, "A", chapters = 3), libNovel(2, "B", chapters = 5)),
            merges = setOf("1,2"),
        )
        result.size shouldBe 1
        val group = result.first()
        group.representative.novel.id shouldBe 2L // more chapters wins the face
        group.memberIds shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `download counts are summed across the group`() {
        val result = collapse(
            listOf(libNovel(1, "A", downloads = 2), libNovel(2, "B", downloads = 3)),
            merges = setOf("1,2"),
        )
        result.first().totalDownloadCount shouldBe 5L
    }

    @Test
    fun `same-title same-author auto-merges with the author guard on`() {
        val result = collapse(listOf(libNovel(1, "A", "X"), libNovel(2, "A", "X")))
        result.size shouldBe 1
        result.first().memberIds shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `same-title different-author stays separate with the guard on`() {
        val result = collapse(listOf(libNovel(1, "A", "X"), libNovel(2, "A", "Y")))
        result.size shouldBe 2
    }

    @Test
    fun `same-title different-author auto-merges with the guard off`() {
        val result = collapse(listOf(libNovel(1, "A", "X"), libNovel(2, "A", "Y")), requireAuthor = false)
        result.size shouldBe 1
        result.first().memberIds shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `a blank author keeps same-title novels apart with the guard on`() {
        val result = collapse(listOf(libNovel(1, "A", null), libNovel(2, "A", null)))
        result.size shouldBe 2
    }

    @Test
    fun `the representative reports the most recent read across the whole group`() {
        // Novel 2 is the representative (more chapters), but novel 1's read is more recent: the
        // merged entry must sort by the group max so reading any source bubbles it up.
        val result = collapse(
            listOf(
                libNovel(1, "A", chapters = 3, lastReadAt = 500),
                libNovel(2, "B", chapters = 5, lastReadAt = 100),
            ),
            merges = setOf("1,2"),
        )
        result.size shouldBe 1
        val group = result.first()
        group.representative.novel.id shouldBe 2L
        group.representative.lastRead shouldBe 500L
    }

    @Test
    fun `an unmerge pair splits a manually-merged bucket`() {
        val result = collapse(
            listOf(libNovel(1, "A"), libNovel(2, "A"), libNovel(3, "A")),
            merges = setOf("1,2,3"),
            unmerges = setOf("1,3"),
            autoMergeSameTitle = false,
        )
        // 1 and 3 can't share a subgroup; greedy placement yields {1,2} and {3}.
        result.size shouldBe 2
        val merged = result.first { it.memberIds.size > 1 }
        merged.memberIds shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }
}
