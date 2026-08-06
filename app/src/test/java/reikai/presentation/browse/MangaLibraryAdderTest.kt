package reikai.presentation.browse

import eu.kanade.domain.manga.interactor.UpdateManga
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.db.PassThroughTransactions
import reikai.domain.manga.MangaMergeManager
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga

/**
 * The add-to-group pair. Membership is not favorite-filtered, so a merge that lands without its
 * favorite leaves an entry feeding chapters into the group while invisible in the library, which no
 * screen can then reach to unmerge. Case for case the twin of
 * [reikai.presentation.novel.browse.NovelLibraryAdderTest].
 */
class MangaLibraryAdderTest {

    private val manga = mockk<Manga> {
        every { id } returns 1L
        every { source } returns 99L
    }

    private fun category(id: Long) = Category(id = id, name = "category $id", order = 0L, flags = 0L)

    private fun adder(
        favoriteWriteSucceeds: Boolean = true,
        alreadyFavorite: Boolean = false,
        rowExists: Boolean = true,
        groupCategories: List<Category> = emptyList(),
        userCategories: List<Category> = emptyList(),
        defaultCategoryId: Int = -1,
        mergeManager: MangaMergeManager = mockk(relaxed = true),
        setMangaCategories: SetMangaCategories = mockk(relaxed = true),
        updateManga: UpdateManga = mockk {
            coEvery { awaitUpdateFavorite(any(), any()) } returns favoriteWriteSucceeds
        },
    ) = MangaLibraryAdder(
        sourceManager = mockk(relaxed = true),
        coverCache = mockk(relaxed = true),
        libraryPreferences = mockk(relaxed = true) {
            every { defaultCategory } returns mockk { every { get() } returns defaultCategoryId }
        },
        getCategories = mockk {
            every { subscribe() } returns flowOf(userCategories)
            coEvery { await(any<Long>()) } returns groupCategories
        },
        getDuplicateLibraryManga = mockk(relaxed = true),
        getManga = mockk {
            coEvery { await(any()) } returns
                if (rowExists) mockk<Manga> { every { favorite } returns alreadyFavorite } else null
        },
        setMangaCategories = setMangaCategories,
        setMangaDefaultChapterFlags = mockk(relaxed = true),
        updateManga = updateManga,
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
    fun `an already-favorited row is merged without rewriting its favorite`() = runTest {
        val updateManga = mockk<UpdateManga>(relaxed = true)
        val mergeManager = mockk<MangaMergeManager>(relaxed = true)

        adder(alreadyFavorite = true, updateManga = updateManga, mergeManager = mergeManager)
            .addToGroup(manga, listOf(2L))

        // Rewriting favorite would reset dateAdded, moving the entry in a date-added sort for what
        // the user experienced as a grouping change.
        coVerify(exactly = 0) { updateManga.awaitUpdateFavorite(any(), any()) }
        coVerify { mergeManager.merge(listOf(1L, 2L)) }
    }

    @Test
    fun `a row that is gone is not merged`() = runTest {
        val mergeManager = mockk<MangaMergeManager>(relaxed = true)

        adder(rowExists = false, mergeManager = mergeManager).addToGroup(manga, listOf(2L)) shouldBe null

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

    /*
     * The default-category step, the manga twin of NovelLibraryAdderTest's pair. It runs through
     * addToExistingGroup here because manga's step is private, where the novel one is called directly
     * by the novel details screen.
     */

    @Test
    fun `a configured default category files the manga without prompting`() = runTest {
        val setMangaCategories = mockk<SetMangaCategories>(relaxed = true)

        adder(
            userCategories = listOf(category(3L)),
            defaultCategoryId = 3,
            setMangaCategories = setMangaCategories,
        ).addToExistingGroup(manga, listOf(2L)) shouldBe AddFavoriteResult.Added

        coVerify { setMangaCategories.await(1L, listOf(3L)) }
    }

    @Test
    fun `no usable default hands the picker back to the caller`() = runTest {
        val result = adder(userCategories = listOf(category(3L)), defaultCategoryId = -1)
            .addToExistingGroup(manga, listOf(2L))

        result shouldBe AddFavoriteResult.NeedsCategoryChoice(
            listOf(CheckboxState.State.None(category(3L))),
        )
    }
}
