package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.model.Manga

class LibraryMergeMembersTest {

    private fun row(id: Long, members: List<Long> = emptyList()) = LibraryItem(
        libraryManga = LibraryManga(
            manga = Manga.create().copy(id = id),
            categories = emptyList(),
            totalChapters = 0,
            readCount = 0,
            bookmarkCount = 0,
            latestUpload = 0,
            chapterFetchedAt = 0,
            lastRead = 0,
        ),
        downloadCount = 0,
        unreadCount = 0,
        isLocal = false,
        badges = LibraryItem.Badges(downloadCount = 0, unreadCount = 0, isLocal = false, sourceLanguage = ""),
        relatedMangaIds = members,
    )

    private val rows = listOf(row(1), row(2, members = listOf(2, 5, 6)), row(3, members = listOf(3)))
        .associateBy { it.id }

    @Test
    fun `an unmerged row is its own member`() {
        row(1).memberIds() shouldContainExactly listOf(1L)
    }

    @Test
    fun `a merged row stands for every member of its group`() {
        rows.memberIdsOf(listOf(1L, 2L)) shouldContainExactly listOf(1L, 2L, 5L, 6L)
    }

    @Test
    fun `an id with no row contributes no members`() {
        rows.memberIdsOf(listOf(9L, 1L)) shouldContainExactly listOf(1L)
    }

    @Test
    fun `a group of one member is not merged`() {
        rows.anyMerged(listOf(1L, 3L)) shouldBe false
    }

    @Test
    fun `a selection holding a group is merged`() {
        rows.anyMerged(listOf(1L, 2L)) shouldBe true
    }
}
