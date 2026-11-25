package reikai.presentation.library.novels

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel
import kotlin.time.Instant

/**
 * The novel library collapse. The ranking order is SourceRankingTest's; these pin what the collapse hands it
 * (each novel's chapter total, the override, the preferred list) and the group it builds.
 */
class NovelMergeCollapseTest {

    private fun libNovel(
        id: Long,
        title: String,
        author: String? = null,
        source: String = "src",
        chapters: Long = 1,
        dateAdded: Long = 0,
    ) = LibraryNovel(
        novel = Novel.create().copy(
            id = id,
            source = source,
            title = title,
            author = author,
            favoriteAt = Instant.fromEpochMilliseconds(dateAdded),
        ),
        categories = emptyList(),
        totalChapters = chapters,
        readCount = 0,
        bookmarkCount = 0,
        downloadCount = 0,
        latestUpload = null,
        chapterFetchedAt = null,
        lastRead = null,
    )

    private fun collapse(
        library: List<LibraryNovel>,
        membership: Map<Long, Long> = emptyMap(),
        overrideRankings: Map<Long, List<Long>> = emptyMap(),
        preferredSourceIds: List<String> = emptyList(),
    ) = NovelMergeCollapse.collapse(library, membership, mergingEnabled = true, overrideRankings, preferredSourceIds)

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
    fun `the collapse hands the ranking each group's override and the preferred-source list`() {
        // Group 7's override names novel 2, against both its chapter count and the preferred list; group 8
        // has no override, so the preferred list names novel 3, against its chapter count.
        val result = collapse(
            listOf(
                libNovel(1, "A", source = "a", chapters = 5),
                libNovel(2, "B", source = "b", chapters = 3),
                libNovel(3, "C", source = "a", chapters = 3),
                libNovel(4, "D", source = "b", chapters = 5),
            ),
            membership = mapOf(1L to 7L, 2L to 7L, 3L to 8L, 4L to 8L),
            overrideRankings = mapOf(7L to listOf(2L, 1L)),
            preferredSourceIds = listOf("a", "b"),
        )
        result.mapTo(HashSet()) { it.representative.novel.id } shouldBe setOf(2L, 3L)
    }
}
