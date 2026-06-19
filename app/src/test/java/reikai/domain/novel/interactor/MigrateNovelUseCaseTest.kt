package reikai.domain.novel.interactor

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter

class MigrateNovelUseCaseTest {

    private fun chapter(
        id: Long,
        number: Double,
        read: Boolean = false,
        bookmark: Boolean = false,
        progress: Long = 0,
    ) = NovelChapter(
        id = id,
        novelId = 1L,
        url = "u$id",
        name = "Chapter $number",
        read = read,
        bookmark = bookmark,
        lastTextProgress = progress,
        chapterNumber = number,
        sourceOrder = id,
        dateFetch = 0,
        dateUpload = 0,
        page = "",
        isDownloaded = false,
    )

    @Test
    fun `an exact number match copies read bookmark and progress onto the target chapter`() {
        val current = listOf(chapter(1, 1.0, read = true, bookmark = true, progress = 4200))
        val target = listOf(chapter(10, 1.0))

        val result = computeChapterMigration(current, target)

        result.single().let {
            it.id shouldBe 10L
            it.read shouldBe true
            it.bookmark shouldBe true
            it.lastTextProgress shouldBe 4200
        }
    }

    @Test
    fun `unmatched target chapters at or below the highest read number are marked read`() {
        // Source read up to chapter 3; target numbers chapters differently but 1 and 2 fall under 3.
        val current = listOf(
            chapter(1, 1.0, read = true),
            chapter(2, 2.0, read = true),
            chapter(3, 3.0, read = true),
        )
        val target = listOf(chapter(10, 1.5), chapter(11, 2.5), chapter(12, 9.0))

        val result = computeChapterMigration(current, target)

        result.filter { it.read }.map { it.id } shouldContainExactlyInAnyOrder listOf(10L, 11L)
    }

    @Test
    fun `unrecognized target chapter numbers are skipped`() {
        val current = listOf(chapter(1, 1.0, read = true))
        val target = listOf(chapter(10, -1.0))

        computeChapterMigration(current, target).shouldContainExactlyInAnyOrder(emptyList())
    }

    @Test
    fun `a target chapter whose state already matches is not returned`() {
        val current = listOf(chapter(1, 1.0, read = false))
        val target = listOf(chapter(10, 1.0, read = false))

        computeChapterMigration(current, target).shouldContainExactlyInAnyOrder(emptyList())
    }

    @Test
    fun `with nothing read in the source no extra chapters are swept to read`() {
        val current = listOf(chapter(1, 1.0, bookmark = true))
        val target = listOf(chapter(10, 1.0), chapter(11, 2.0))

        val result = computeChapterMigration(current, target)

        // Only the matched chapter changes (gains the bookmark); chapter 2 stays untouched.
        result.single().id shouldBe 10L
    }
}
