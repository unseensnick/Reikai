package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import reikai.data.library.hiddenWithGroups
import reikai.domain.entry.EntryId
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

/** The rule for whether a library row is hidden from the library view, both content types, and its id-set twin. */
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

    /** Entries 3 and 4 are one group with 3 hidden, 5 is alone in its group, 1 in none. */
    @ParameterizedTest
    @ValueSource(longs = [1L, 3L, 4L, 5L])
    fun `the Updates badge hides the entries the library hides`(id: Long) {
        val memberships = mapOf(3L to 7L, 4L to 7L, 5L to 8L)
        val members = memberships[id]?.let { group -> memberships.filterValues { it == group }.keys.toList() }
        val hidden = setOf(3L)

        (id in hiddenWithGroups(hidden, memberships)) shouldBe
            isHiddenFromLibrary(row(id, members = members.orEmpty()), hidden.mapTo(HashSet()) { EntryId.Manga(it) })
    }
}
