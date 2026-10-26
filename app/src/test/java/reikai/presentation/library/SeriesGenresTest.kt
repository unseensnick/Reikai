package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import mihon.domain.library.model.search.QueryNode
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** A series' tags as the library search and Statistics both read them, so a Tags row finds what it counts. */
class SeriesGenresTest {

    @Test
    fun `a merged series is found by a tag only another source gives it`() {
        matches(merged(lead = listOf("Action"), members = listOf("Action", "Romance")), "tag:Romance") shouldBe true
    }

    @Test
    fun `Edit info tags replace every source's own`() {
        val overlay = mapOf(1L to LibraryQueryOverlay(null, null, null, null, listOf("Drama")))

        matches(merged(lead = listOf("Action"), members = listOf("Romance")), "tag:Romance", overlay) shouldBe false
    }

    @Test
    fun `a tag two sources spell in different case counts once`() {
        seriesGenres(null, listOf(listOf("Action"), listOf("action", "Drama"))) shouldBe listOf("Action", "Drama")
    }

    private fun matches(row: LibraryItem, query: String, overlay: Map<Long, LibraryQueryOverlay> = emptyMap()) =
        libraryQueryMatches(QueryNode.from(query), row, libraryItemQueryFields(sourceKey = { "1" }, overlay = overlay))

    private fun merged(lead: List<String>, members: List<String>) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = 1L, genre = lead),
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = null,
            chapterFetchedAt = null,
            lastRead = null,
        ),
        downloadCount = 0,
        unreadCount = 0,
        isLocal = false,
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        memberGenres = members,
    )
}
