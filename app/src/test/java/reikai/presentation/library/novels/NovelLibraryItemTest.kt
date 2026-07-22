package reikai.presentation.library.novels

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.model.Novel

class NovelLibraryItemTest {

    private fun libraryNovel(id: Long) = LibraryNovel(
        novel = Novel.create().copy(id = id, title = "Title", url = "/n/$id", source = "src"),
        categories = emptyList(),
        totalChapters = 0,
        readCount = 0,
        bookmarkCount = 0,
        downloadCount = 0,
        latestUpload = 0,
        chapterFetchedAt = 0,
    )

    private fun item(id: Long) = libraryNovel(id).toLibraryItem(
        downloadBadge = false,
        unreadBadge = false,
        languageBadge = false,
        sourceLanguage = "en",
        sourceBadge = false,
        sourceSite = null,
        sourceIconUrl = null,
    )

    @Test
    fun `row carries the novel's own id, not a negated one`() {
        item(12L).id shouldBe 12L
    }

    @Test
    fun `identity is the novel entry id`() {
        item(12L).entryId shouldBe EntryId.Novel(12L)
    }

    @Test
    fun `a novel and a manga sharing a row id are different entries`() {
        val novel = item(12L).entryId
        val manga = EntryId.Manga(12L)

        (novel == manga) shouldBe false
    }
}
