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
            id = id,
            title = title,
            author = author,
            favorite = true,
            dateAdded = dateAdded,
            lastReadAt = lastReadAt,
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
        membership: Map<Long, Long> = emptyMap(),
        mergingEnabled: Boolean = true,
    ) = NovelMergeCollapse.collapse(library, membership, mergingEnabled)

    @Test
    fun `a lone novel is its own single-member group`() {
        val result = collapse(listOf(libNovel(1, "A")))
        result.size shouldBe 1
        result.first().memberIds shouldContainExactlyInAnyOrder listOf(1L)
    }

    @Test
    fun `grouped novels collapse to the most-chapters representative`() {
        val result = collapse(
            listOf(libNovel(1, "A", chapters = 3), libNovel(2, "B", chapters = 5)),
            membership = mapOf(1L to 7L, 2L to 7L),
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
            membership = mapOf(1L to 7L, 2L to 7L),
        )
        result.first().totalDownloadCount shouldBe 5L
    }

    @Test
    fun `ungrouped novels stay separate`() {
        val result = collapse(listOf(libNovel(1, "A"), libNovel(2, "A")), membership = emptyMap())
        result.size shouldBe 2
    }

    @Test
    fun `merging disabled keeps every novel its own entry`() {
        val result = collapse(
            listOf(libNovel(1, "A"), libNovel(2, "A")),
            membership = mapOf(1L to 7L, 2L to 7L),
            mergingEnabled = false,
        )
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
            membership = mapOf(1L to 7L, 2L to 7L),
        )
        result.size shouldBe 1
        val group = result.first()
        group.representative.novel.id shouldBe 2L
        group.representative.lastRead shouldBe 500L
    }
}
