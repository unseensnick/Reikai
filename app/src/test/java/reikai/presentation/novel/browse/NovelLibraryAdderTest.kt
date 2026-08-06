package reikai.presentation.novel.browse

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.GetNovelCategories
import reikai.domain.db.Transactions
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import tachiyomi.domain.category.model.Category

/**
 * The novel twin of [reikai.presentation.browse.MangaLibraryAdderTest]. Membership is not
 * favorite-filtered, so a merge that lands without its favorite leaves an entry feeding chapters into
 * the group while invisible in the library, which no screen can then reach to unmerge.
 */
class NovelLibraryAdderTest {

    /** Runs the block for real, so a test sees what the transaction would commit. */
    private val transactions = object : Transactions {
        override suspend fun <T> run(block: suspend () -> T): T = block()
    }

    private fun adder(
        favoriteWriteSucceeds: Boolean = true,
        alreadyFavorite: Boolean = false,
        rowExists: Boolean = true,
        groupCategories: List<Category> = emptyList(),
        mergeManager: NovelMergeManager = mockk(relaxed = true),
        setNovelCategories: SetNovelCategories = mockk(relaxed = true),
        updateNovel: UpdateNovel = mockk {
            coEvery { awaitUpdateFavorite(any(), any()) } returns favoriteWriteSucceeds
        },
    ) = NovelLibraryAdder(
        novelRepository = mockk<NovelRepository> {
            coEvery { getById(any()) } returns
                if (rowExists) mockk<Novel> { every { favorite } returns alreadyFavorite } else null
        },
        manager = mockk(relaxed = true),
        getNovelCategories = mockk<GetNovelCategories> {
            coEvery { awaitByNovelId(any()) } returns groupCategories
        },
        setNovelCategories = setNovelCategories,
        updateNovel = updateNovel,
        novelPreferences = mockk(relaxed = true),
        mergeManager = mergeManager,
        transactions = transactions,
    )

    @Test
    fun `a failed favorite write leaves the novel out of the group`() = runTest {
        val mergeManager = mockk<NovelMergeManager>(relaxed = true)

        val result = adder(favoriteWriteSucceeds = false, mergeManager = mergeManager)
            .addToGroup(1L, listOf(2L))

        result shouldBe null
        coVerify(exactly = 0) { mergeManager.merge(any()) }
    }

    @Test
    fun `an already-favorited row is merged without rewriting its favorite`() = runTest {
        val updateNovel = mockk<UpdateNovel>(relaxed = true)
        val mergeManager = mockk<NovelMergeManager>(relaxed = true)

        adder(alreadyFavorite = true, updateNovel = updateNovel, mergeManager = mergeManager)
            .addToGroup(1L, listOf(2L))

        // Rewriting favorite would reset dateAdded, moving the entry in a date-added sort for what
        // the user experienced as a grouping change.
        coVerify(exactly = 0) { updateNovel.awaitUpdateFavorite(any(), any()) }
        coVerify { mergeManager.merge(listOf(1L, 2L)) }
    }

    @Test
    fun `the group's categories are seeded onto the new member`() = runTest {
        val setNovelCategories = mockk<SetNovelCategories>(relaxed = true)
        val category = mockk<Category> { every { id } returns 5L }

        val seeded = adder(groupCategories = listOf(category), setNovelCategories = setNovelCategories)
            .addToGroup(1L, listOf(2L))

        seeded shouldBe true
        coVerify { setNovelCategories.await(1L, listOf(5L)) }
    }

    @Test
    fun `an uncategorized group reports back so the caller can fall back to its own prompt`() = runTest {
        adder(groupCategories = emptyList()).addToGroup(1L, listOf(2L)) shouldBe false
    }

    @Test
    fun `a row that is gone is not merged`() = runTest {
        val mergeManager = mockk<NovelMergeManager>(relaxed = true)

        adder(rowExists = false, mergeManager = mergeManager).addToGroup(1L, listOf(2L)) shouldBe null

        coVerify(exactly = 0) { mergeManager.merge(any()) }
    }
}
