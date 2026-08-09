package reikai.presentation.novel.browse

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.GetNovelCategories
import reikai.domain.db.PassThroughTransactions
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.SetNovelCategories
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.novel.host.NovelItem
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.model.Category

/**
 * What only novels can do, so it has no manga counterpart: a browsed novel has no library row until
 * the add creates one, where a browsed manga always has one already. That is the whole reason a novel
 * picker can be reached with nothing written yet. Everything both types share is pinned once, in
 * [reikai.presentation.browse.AddToGroupConformanceTest] and its sibling suites.
 */
class NovelLibraryAdderTest {

    private fun category(id: Long) = Category(id = id, name = "category $id", order = 0L, flags = 0L)

    private val item = NovelItem(name = "a novel", path = "/a-novel", cover = null)

    private fun repositoryStub(): NovelRepository = mockk {
        coEvery { getById(any()) } returns mockk<Novel> { every { favorite } returns false }
        coEvery { getByUrlAndSource(any(), any()) } returns null
        coEvery { insertOrGet(any()) } returns mockk<Novel> {
            every { id } returns 5L
            every { favorite } returns false
        }
    }

    private fun adder(
        favoriteWriteSucceeds: Boolean = true,
        alreadyFavorite: Boolean = false,
        rowExists: Boolean = true,
        groupCategories: List<Category> = emptyList(),
        userCategories: List<Category> = emptyList(),
        defaultCategoryId: Int = -1,
        mergeManager: NovelMergeManager = mockk(relaxed = true),
        setNovelCategories: SetNovelCategories = mockk(relaxed = true),
        updateNovel: UpdateNovel = mockk {
            coEvery { awaitUpdateFavorite(any(), any()) } returns favoriteWriteSucceeds
        },
        novelRepository: NovelRepository = mockk {
            coEvery { getById(any()) } returns
                if (rowExists) mockk<Novel> { every { favorite } returns alreadyFavorite } else null
            coEvery { getByUrlAndSource(any(), any()) } returns null
            coEvery { insertOrGet(any()) } returns mockk<Novel> {
                every { id } returns 5L
                every { favorite } returns false
            }
        },
    ) = NovelLibraryAdder(
        novelRepository = novelRepository,
        manager = mockk(relaxed = true),
        getNovelCategories = mockk<GetNovelCategories> {
            coEvery { await() } returns userCategories
            coEvery { awaitByNovelId(any()) } returns groupCategories
        },
        setNovelCategories = setNovelCategories,
        updateNovel = updateNovel,
        novelPreferences = mockk(relaxed = true) {
            every { defaultNovelCategory() } returns mockk { every { get() } returns defaultCategoryId }
        },
        mergeManager = mergeManager,
        transactions = PassThroughTransactions,
        reikaiLibraryPreferences = mockk {
            every { categorySortOrder } returns mockk { every { get() } returns 0 }
        },
    )

    @Test
    fun `a browse add that needs the picker writes nothing before it`() = runTest {
        val updateNovel = mockk<UpdateNovel>(relaxed = true)
        val setNovelCategories = mockk<SetNovelCategories>(relaxed = true)
        val repository = repositoryStub()

        adder(
            userCategories = listOf(category(3L)),
            defaultCategoryId = -1,
            setNovelCategories = setNovelCategories,
            updateNovel = updateNovel,
            novelRepository = repository,
        ).addToLibrary(item, sourceId = "src")

        coVerify(exactly = 0) {
            repository.insertOrGet(any())
            updateNovel.awaitUpdateFavorite(any(), any())
            setNovelCategories.await(any(), any())
        }
    }

    @Test
    fun `a browse add that needs the picker asks with the add still pending`() = runTest {
        val dialog = adder(userCategories = listOf(category(3L)), defaultCategoryId = -1)
            .addToLibrary(item, sourceId = "src")

        dialog shouldBe NovelBrowseDialog.ChangeCategory(
            NovelCategoryTarget.Pending(item, "src"),
            listOf(CheckboxState.State.None(category(3L))),
        )
    }

    @Test
    fun `confirming a pending picker creates the row, favorites it, then files it`() = runTest {
        val updateNovel = mockk<UpdateNovel> {
            coEvery { awaitUpdateFavorite(any(), any()) } returns true
        }
        val setNovelCategories = mockk<SetNovelCategories>(relaxed = true)

        adder(setNovelCategories = setNovelCategories, updateNovel = updateNovel)
            .confirmCategories(NovelCategoryTarget.Pending(item, "src"), listOf(3L))

        coVerifyOrder {
            updateNovel.awaitUpdateFavorite(5L, true)
            setNovelCategories.await(5L, listOf(3L))
        }
    }

    @Test
    fun `confirming after add-time grouping only files, since it favorited up front`() = runTest {
        val updateNovel = mockk<UpdateNovel>(relaxed = true)

        adder(updateNovel = updateNovel)
            .confirmCategories(NovelCategoryTarget.Stored(9L), listOf(3L))

        coVerify(exactly = 0) { updateNovel.awaitUpdateFavorite(any(), any()) }
    }

    @Test
    fun `an already favorited row is not re-favorited, which would reset its add date`() = runTest {
        val updateNovel = mockk<UpdateNovel>(relaxed = true)

        adder(alreadyFavorite = true, updateNovel = updateNovel).favoriteForAdd(1L)

        coVerify(exactly = 0) { updateNovel.awaitUpdateFavorite(any(), any()) }
    }
}
