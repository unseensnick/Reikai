package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.chapter.model.Chapter

/**
 * Where a reader puts a chapter it opens that the merged list does not show. Each reader then sorts on
 * source order, so what a test checks is the list sorted the way the readers sort it.
 */
class OpenedChapterTest {

    private fun novelChapter(id: Long, novelId: Long, number: Double, order: Long) = NovelChapter(
        id = id,
        novelId = novelId,
        url = "/$id",
        name = "Chapter $number",
        read = false,
        bookmark = false,
        lastTextProgress = 0L,
        chapterNumber = number,
        sourceOrder = order,
        dateFetch = 0L,
        dateUpload = 0L,
        page = "",
    )

    /** How the novel readers call the kernel: their lists run oldest-first. */
    private fun novelSession(unified: List<NovelChapter>, opened: NovelChapter, stitch: List<ChapterUnit>) =
        withOpenedChapter(
            unified = unified,
            opened = opened,
            stitch = stitch,
            id = { it.id },
            byNumber = compareBy { it.chapterNumber },
            restamp = { chapter, order -> chapter.copy(sourceOrder = order) },
        ).sortedBy { it.sourceOrder }

    /**
     * Source A holds chapters 1 to 10 and leads the stitch; source B holds only 5 to 7, numbered on its
     * own list from 0. B's copy of chapter 6 is what History recorded.
     */
    private val trunk = (1L..10L).map { novelChapter(it, novelId = 1L, number = it.toDouble(), order = it - 1) }
    private val siblingSix = novelChapter(106L, novelId = 2L, number = 6.0, order = 1L)
    private val stitch = trunk.mapIndexed { index, chapter -> ChapterUnit(chapter.id, index, 0) } +
        listOf(ChapterUnit(105L, 4, 1), ChapterUnit(106L, 5, 1), ChapterUnit(107L, 6, 1))

    @Test
    fun `a sibling's copy opened from history takes its merged chapter's place`() {
        novelSession(trunk, siblingSix, stitch).map { it.id } shouldBe listOf(1L, 2L, 3L, 4L, 5L, 106L, 7L, 8L, 9L, 10L)
    }

    @Test
    fun `a chapter the stitch places nowhere goes by its number in an oldest-first list`() {
        val unified = (1L..3L).map { novelChapter(it, novelId = 1L, number = it.toDouble(), order = it - 1) }
        val opened = novelChapter(9L, 1L, 2.5, 40L)

        novelSession(unified, opened, emptyList()).map { it.id } shouldBe listOf(1L, 2L, 9L, 3L)
    }

    @Test
    fun `a chapter the stitch places nowhere goes by its number in a newest-first list`() {
        // The manga reader's list runs newest-first, as its sources' own lists do.
        val unified = listOf(3.0, 2.0, 1.0).mapIndexed { index, number ->
            Chapter.create().copy(id = index + 1L, chapterNumber = number, sourceOrder = index.toLong())
        }
        val opened = Chapter.create().copy(id = 9L, chapterNumber = 2.5, sourceOrder = 42L)

        withOpenedChapter(
            unified = unified,
            opened = opened,
            stitch = emptyList(),
            id = { it.id },
            byNumber = compareByDescending { it.chapterNumber },
            restamp = { chapter, order -> chapter.copy(sourceOrder = order) },
        ).sortedBy { it.sourceOrder }.map { it.id } shouldBe listOf(1L, 9L, 2L, 3L)
    }

    @Test
    fun `putting a chapter back renumbers the list onto one source order`() {
        novelSession(trunk, siblingSix, stitch).map { it.sourceOrder } shouldBe (0L..9L).toList()
    }

    @Test
    fun `a chapter the list already shows leaves it untouched`() {
        novelSession(trunk, trunk[3], stitch) shouldBe trunk
    }

    @Test
    fun `a chapter the list already shows is not added again when the stitch places it nowhere`() {
        // With no stitch the placement goes by number, which would slot a second copy in beside it.
        novelSession(trunk, trunk[3], emptyList()).map { it.id } shouldBe trunk.map { it.id }
    }
}
