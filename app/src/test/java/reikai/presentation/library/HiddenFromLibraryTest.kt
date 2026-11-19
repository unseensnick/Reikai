package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** The one rule for whether a library row is hidden from the library view, both content types. */
class HiddenFromLibraryTest {

    private fun row(id: Long, novel: Boolean = false, members: List<Long> = emptyList()) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = id),
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
        relatedMangaIds = members,
        entryId = if (novel) EntryId.Novel(id) else EntryId.Manga(id),
    )

    @Test
    fun `a hidden manga row is hidden`() {
        isHiddenFromLibrary(row(5), setOf(EntryId.Manga(5))) shouldBe true
    }

    @Test
    fun `a hidden manga does not hide the novel sharing its id`() {
        isHiddenFromLibrary(row(5, novel = true), setOf(EntryId.Manga(5))) shouldBe false
    }

    @Test
    fun `a hidden novel does not hide the manga sharing its id`() {
        isHiddenFromLibrary(row(5), setOf(EntryId.Novel(5))) shouldBe false
    }

    @Test
    fun `a merged novel row is hidden through a non-lead member`() {
        isHiddenFromLibrary(row(5, novel = true, members = listOf(5, 9)), setOf(EntryId.Novel(9))) shouldBe true
    }

    @Test
    fun `a merged manga row is hidden through a non-lead member`() {
        isHiddenFromLibrary(row(5, members = listOf(5, 9)), setOf(EntryId.Manga(9))) shouldBe true
    }
}
