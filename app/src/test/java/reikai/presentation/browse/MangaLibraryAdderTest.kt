package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.db.PassThroughTransactions
import reikai.domain.manga.MangaMergeManager
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga

/**
 * The add-to-group pair. Membership is not favorite-filtered, so a merge that lands without its
 * favorite leaves an entry feeding chapters into the group while invisible in the library, which no
 * screen can then reach to unmerge.
 */
class MangaLibraryAdderTest {

    private val manga = mockk<Manga> {
        every { id } returns 1L
        every { source } returns 99L
    }

    private fun adder(
        favoriteWriteSucceeds: Boolean = true,
        groupCategories: List<Category> = emptyList(),
        mergeManager: MangaMergeManager = mockk(relaxed = true),
        setMangaCategories: SetMangaCategories = mockk(relaxed = true),
    ) = MangaLibraryAdder(
        sourceManager = mockk(relaxed = true),
        coverCache = mockk(relaxed = true),
        libraryPreferences = mockk(relaxed = true),
        getCategories = mockk {
            coEvery { await(any<Long>()) } returns groupCategories
        },
        getDuplicateLibraryManga = mockk(relaxed = true),
        setMangaCategories = setMangaCategories,
        setMangaDefaultChapterFlags = mockk(relaxed = true),
        updateManga = mockk {
            coEvery { awaitUpdateFavorite(any(), any()) } returns favoriteWriteSucceeds
        },
        addTracks = mockk(relaxed = true),
        mergeManager = mergeManager,
        transactions = PassThroughTransactions,
    )

    @Test
    fun `a failed favorite write leaves the manga out of the group`() = runTest {
        val mergeManager = mockk<MangaMergeManager>(relaxed = true)

        val result = adder(favoriteWriteSucceeds = false, mergeManager = mergeManager)
            .addToGroup(manga, listOf(2L))

        result shouldBe null
        coVerify(exactly = 0) { mergeManager.merge(any()) }
    }

    @Test
    fun `the group's categories are seeded onto the new member`() = runTest {
        val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)
        val category = mockk<Category> { every { id } returns 5L }

        val seeded = adder(groupCategories = listOf(category), setMangaCategories = setMangaCategories)
            .addToGroup(manga, listOf(2L))

        seeded shouldBe true
        coVerify { setMangaCategories.await(1L, listOf(5L)) }
    }

    @Test
    fun `an uncategorized group reports back so the caller can fall back to its own prompt`() = runTest {
        adder(groupCategories = emptyList()).addToGroup(manga, listOf(2L)) shouldBe false
    }
}
