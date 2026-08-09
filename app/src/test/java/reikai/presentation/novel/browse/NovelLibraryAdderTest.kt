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
import tachiyomi.domain.category.model.Category

/**
 * The novel twin of [reikai.presentation.browse.MangaLibraryAdderTest]. Membership is not
 * favorite-filtered, so a merge that lands without its favorite leaves an entry feeding chapters into
 * the group while invisible in the library, which no screen can then reach to unmerge.
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

    /*
     * The default-category step, which the novel details screen reaches directly and every other novel
     * add path reaches through this class. Its manga twin goes through addToExistingGroup, because
     * manga's equivalent step is private.
     */

    @Test
    fun `a configured default category files the novel without prompting`() = runTest {
        val setNovelCategories = mockk<SetNovelCategories>(relaxed = true)

        adder(
            userCategories = listOf(category(3L)),
            defaultCategoryId = 3,
            setNovelCategories = setNovelCategories,
        ).applyDefaultCategoryOrPrompt(1L) shouldBe null

        coVerify { setNovelCategories.await(1L, listOf(3L)) }
    }

    @Test
    fun `no usable default hands the picker back to the caller`() = runTest {
        val prompt = adder(userCategories = listOf(category(3L)), defaultCategoryId = -1)
            .applyDefaultCategoryOrPrompt(1L)

        prompt?.categories shouldBe listOf(category(3L))
    }

    @Test
    fun `a row that is gone is not merged`() = runTest {
        val mergeManager = mockk<NovelMergeManager>(relaxed = true)

        adder(rowExists = false, mergeManager = mergeManager).addToGroup(1L, listOf(2L)) shouldBe null

        coVerify(exactly = 0) { mergeManager.merge(any()) }
    }

    /**
     * Novel-only, because a browsed novel has no library row until the add creates one, where a browsed
     * manga always has one. So only here can the picker be reached with nothing written yet.
     */

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
            listOf(category(3L)),
            emptySet(),
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
