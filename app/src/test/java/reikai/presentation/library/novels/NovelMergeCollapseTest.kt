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
        source: String = "src",
        chapters: Long = 1,
        downloads: Long = 0,
        dateAdded: Long = 0,
        lastReadAt: Long? = null,
    ) = LibraryNovel(
        novel = Novel.create().copy(
            id = id,
            source = source,
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
        overrideRankings: Map<Long, List<Long>> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
    ) = NovelMergeCollapse.collapse(library, membership, mergingEnabled, overrideRankings, preferredSourceIds)

    @Test
    fun `a lone novel is its own single-member group`() {
        val result = collapse(listOf(libNovel(1, "A")))
        result.size shouldBe 1
        result.first().memberIds shouldContainExactlyInAnyOrder listOf(1L)
    }

    @Test
    fun `with no ranking set the most-chapters novel is the representative`() {
        // No per-group override and no preferred-source list: the representative falls back to the
        // most-chapters novel (then lowest id), matching the details trunk's own fallback.
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
    fun `a per-group override picks the representative over chapter count`() {
        // The override orders members [2, 1] (member 2 is the trunk) even though member 1 has more
        // chapters, so the library row leads on the same source the details chapter list trunks on.
        val result = collapse(
            listOf(libNovel(1, "A", chapters = 5), libNovel(2, "B", chapters = 3)),
            membership = mapOf(1L to 7L, 2L to 7L),
            overrideRankings = mapOf(7L to listOf(2L, 1L)),
        )
        result.single().representative.novel.id shouldBe 2L // override trunk wins despite fewer chapters
    }

    @Test
    fun `the global preferred-source list picks the representative when no override is set`() {
        // Source "b" outranks "a" in the global list, so its member is the representative even with fewer
        // chapters. The override map is empty, so this is the fallback ranking.
        val result = collapse(
            listOf(
                libNovel(1, "A", source = "a", chapters = 5),
                libNovel(2, "B", source = "b", chapters = 3),
            ),
            membership = mapOf(1L to 7L, 2L to 7L),
            preferredSourceIds = listOf("b", "a"),
        )
        result.single().representative.novel.id shouldBe 2L // preferred source wins despite fewer chapters
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
