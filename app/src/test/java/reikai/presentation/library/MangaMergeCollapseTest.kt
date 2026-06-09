package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.Source

class MangaMergeCollapseTest {

    private val resolveSource: (Long) -> Source = { id ->
        Source(id = id, lang = "en", name = "Source $id", supportsLatest = false, isStub = false)
    }

    private fun item(
        id: Long,
        title: String = "Title",
        source: Long = 100L,
        totalChapters: Long = 0,
        dateAdded: Long = 0,
        unread: Long = 0,
    ): LibraryItem {
        val manga = Manga.create().copy(id = id, source = source, title = title, dateAdded = dateAdded)
        return LibraryItem(
            libraryManga = LibraryManga(
                manga = manga,
                categories = emptyList(),
                totalChapters = totalChapters,
                readCount = 0,
                bookmarkCount = 0,
                latestUpload = 0,
                chapterFetchedAt = 0,
                lastRead = 0,
            ),
            downloadCount = 0,
            unreadCount = unread,
            isLocal = false,
            badges = LibraryItem.Badges(downloadCount = 0, unreadCount = unread, isLocal = false, sourceLanguage = ""),
        )
    }

    private fun collapse(
        items: List<LibraryItem>,
        merges: Set<String> = emptySet(),
        unmerges: Set<String> = emptySet(),
        auto: Boolean = true,
    ) = MangaMergeCollapse.collapse(items, merges, unmerges, auto, showMergeSourceIcons = true, resolveSource)

    @Test
    fun `a single item is returned unchanged`() {
        val items = listOf(item(1))
        collapse(items) shouldBe items
    }

    @Test
    fun `same-title items collapse to one entry with summed unread and the larger primary`() {
        val result = collapse(
            listOf(
                item(1, title = "One Piece", source = 100L, totalChapters = 5, unread = 2),
                item(2, title = "One Piece", source = 200L, totalChapters = 10, unread = 3),
            ),
        )
        result.size shouldBe 1
        val merged = result.single()
        merged.id shouldBe 2L // more chapters wins the primary
        merged.unreadCount shouldBe 5L
        merged.relatedMangaIds shouldContainExactlyInAnyOrder listOf(1L, 2L)
        merged.badges.mergedSources.map { it.id } shouldContainExactlyInAnyOrder listOf(100L, 200L)
    }

    @Test
    fun `auto-merge off keeps same-title items separate`() {
        val result = collapse(
            listOf(item(1, title = "Same"), item(2, title = "Same")),
            auto = false,
        )
        result.map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `a manual merge entry collapses items with different titles`() {
        val result = collapse(
            listOf(item(1, title = "Alpha"), item(2, title = "Beta")),
            merges = setOf("1,2"),
            auto = false,
        )
        result.size shouldBe 1
        result.single().relatedMangaIds shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `an unmerge pair splits a same-title bucket`() {
        val result = collapse(
            listOf(item(1, title = "Dupe"), item(2, title = "Dupe")),
            unmerges = setOf("1,2"),
        )
        result.map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `the most recently added wins the primary when chapter counts tie`() {
        val result = collapse(
            listOf(
                item(1, title = "Tie", totalChapters = 5, dateAdded = 200),
                item(2, title = "Tie", totalChapters = 5, dateAdded = 100),
            ),
        )
        result.single().id shouldBe 1L // same chapters; max date_added wins the tiebreak
    }
}
