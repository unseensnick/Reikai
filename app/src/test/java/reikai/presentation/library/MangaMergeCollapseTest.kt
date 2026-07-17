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
        lastRead: Long = 0,
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
                lastRead = lastRead,
            ),
            downloadCount = 0,
            unreadCount = unread,
            isLocal = false,
            badges = LibraryItem.Badges(downloadCount = 0, unreadCount = unread, isLocal = false, sourceLanguage = ""),
        )
    }

    private fun collapse(
        items: List<LibraryItem>,
        membership: Map<Long, Long> = emptyMap(),
        mergingEnabled: Boolean = true,
    ) = MangaMergeCollapse.collapse(items, membership, mergingEnabled, showMergeSourceIcons = true, resolveSource)

    @Test
    fun `a single item is returned unchanged`() {
        val items = listOf(item(1))
        collapse(items) shouldBe items
    }

    @Test
    fun `grouped items collapse to one entry with the most-chapters primary`() {
        val result = collapse(
            listOf(
                item(1, source = 100L, totalChapters = 5, unread = 2),
                item(2, source = 200L, totalChapters = 10, unread = 3),
            ),
            membership = mapOf(1L to 7L, 2L to 7L),
        )
        result.size shouldBe 1
        val merged = result.single()
        merged.id shouldBe 2L // more chapters wins the primary
        merged.unreadCount shouldBe 3L // primary's unread (not summed), closer to the deduped count
        merged.relatedMangaIds shouldContainExactlyInAnyOrder listOf(1L, 2L)
        merged.badges.mergedSources.map { it.id } shouldContainExactlyInAnyOrder listOf(100L, 200L)
    }

    @Test
    fun `ungrouped items stay separate`() {
        val result = collapse(listOf(item(1), item(2)), membership = emptyMap())
        result.map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `merging disabled returns items unchanged`() {
        val items = listOf(item(1), item(2))
        collapse(items, membership = mapOf(1L to 7L, 2L to 7L), mergingEnabled = false) shouldBe items
    }

    @Test
    fun `the merged entry reports the most recent read across the whole group`() {
        // Manga 1 is the primary (more chapters), but manga 2's read is more recent: the merged entry
        // must sort by the group max so reading any source bubbles it up.
        val result = collapse(
            listOf(
                item(1, totalChapters = 10, lastRead = 100),
                item(2, totalChapters = 5, lastRead = 500),
            ),
            membership = mapOf(1L to 7L, 2L to 7L),
        )
        result.size shouldBe 1
        val merged = result.single()
        merged.id shouldBe 1L
        merged.libraryManga.lastRead shouldBe 500L
    }

    @Test
    fun `the most recently added wins the primary when chapter counts tie`() {
        val result = collapse(
            listOf(
                item(1, totalChapters = 5, dateAdded = 200),
                item(2, totalChapters = 5, dateAdded = 100),
            ),
            membership = mapOf(1L to 7L, 2L to 7L),
        )
        result.single().id shouldBe 1L // same chapters; max date_added wins the tiebreak
    }
}
