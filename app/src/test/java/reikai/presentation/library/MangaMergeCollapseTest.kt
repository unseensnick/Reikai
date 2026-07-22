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
        isLocal: Boolean = false,
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
            isLocal = isLocal,
            badges = LibraryItem.Badges(
                downloadCount = 0,
                unreadCount = unread,
                isLocal = isLocal,
                sourceLanguage = "",
            ),
        )
    }

    private fun collapse(
        items: List<LibraryItem>,
        membership: Map<Long, Long> = emptyMap(),
        mergingEnabled: Boolean = true,
        overrideRankings: Map<Long, List<Long>> = emptyMap(),
        preferredSourceIds: List<Long> = emptyList(),
    ) = MangaMergeCollapse.collapse(
        items,
        membership,
        mergingEnabled,
        showMergeSourceIcons = true,
        resolveSource,
        overrideRankings = overrideRankings,
        preferredSourceIds = preferredSourceIds,
    )

    @Test
    fun `a single item is returned unchanged`() {
        val items = listOf(item(1))
        collapse(items) shouldBe items
    }

    @Test
    fun `with no ranking set the most-chapters source is the primary`() {
        // No per-group override and no preferred-source list: the primary falls back to the most-chapters
        // source (then lowest id), matching the details trunk's own fallback.
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
    fun `a per-group override picks the primary over chapter count`() {
        // The override orders members [2, 1] (member 2 is the trunk) even though member 1 has more
        // chapters, so the library row leads on the same source the details chapter list trunks on.
        val result = collapse(
            listOf(
                item(1, source = 100L, totalChapters = 10),
                item(2, source = 200L, totalChapters = 3),
            ),
            membership = mapOf(1L to 7L, 2L to 7L),
            overrideRankings = mapOf(7L to listOf(2L, 1L)),
        )
        result.single().id shouldBe 2L // override trunk wins despite fewer chapters
    }

    @Test
    fun `the global preferred-source list picks the primary when no override is set`() {
        // Source 200 outranks source 100 in the global list, so its member is the primary even with fewer
        // chapters. The override map is empty, so this is the fallback ranking.
        val result = collapse(
            listOf(
                item(1, source = 100L, totalChapters = 10),
                item(2, source = 200L, totalChapters = 3),
            ),
            membership = mapOf(1L to 7L, 2L to 7L),
            preferredSourceIds = listOf(200L, 100L),
        )
        result.single().id shouldBe 2L // preferred source wins despite fewer chapters
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
    fun `a local source chosen as the trunk keeps the collapsed entry local`() {
        // The Local badge and whether Download is offered read the representative's own isLocal, so a
        // local source promoted to the trunk (via the override) must carry its local state onto the row.
        // Local member 1 has fewer chapters, so only the ranking, not the count, makes it the trunk.
        val result = collapse(
            listOf(
                item(1, source = 100L, totalChapters = 3, isLocal = true),
                item(2, source = 200L, totalChapters = 10, isLocal = false),
            ),
            membership = mapOf(1L to 7L, 2L to 7L),
            overrideRankings = mapOf(7L to listOf(1L, 2L)),
        )
        val merged = result.single()
        merged.isLocal shouldBe true
        merged.badges.isLocal shouldBe true
    }

    @Test
    fun `a remote source chosen as the trunk keeps the collapsed entry non-local`() {
        // The inverse: a remote source promoted to the trunk over a higher-chapter local member leaves the
        // row non-local, so Download stays available.
        val result = collapse(
            listOf(
                item(1, source = 100L, totalChapters = 10, isLocal = true),
                item(2, source = 200L, totalChapters = 3, isLocal = false),
            ),
            membership = mapOf(1L to 7L, 2L to 7L),
            overrideRankings = mapOf(7L to listOf(2L, 1L)),
        )
        val merged = result.single()
        merged.isLocal shouldBe false
        merged.badges.isLocal shouldBe false
    }

    @Test
    fun `the lowest id wins the primary when counts tie and no ranking is set`() {
        // Same chapters, no override, no preferred list: the tiebreak is the lowest id (the aggregation's
        // deterministic tiebreak), NOT date_added. Member 2 was added later but member 1 (lower id) wins.
        val result = collapse(
            listOf(
                item(1, totalChapters = 5, dateAdded = 100),
                item(2, totalChapters = 5, dateAdded = 200),
            ),
            membership = mapOf(1L to 7L, 2L to 7L),
        )
        result.single().id shouldBe 1L // lowest id wins the tiebreak, regardless of date_added
    }
}
