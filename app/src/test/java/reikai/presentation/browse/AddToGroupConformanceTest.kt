package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.category.GetNovelCategories
import reikai.domain.db.PassThroughTransactions
import reikai.domain.manga.MangaMergeManager
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.host.NovelItem
import reikai.presentation.novel.browse.NovelBrowseDialog
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga

/**
 * Add-time grouping, pinned once for both content types. Membership is not favorite-filtered, so a
 * merge that lands without its favorite leaves an entry feeding chapters into the group while
 * invisible in the library, which no screen can then reach to unmerge. That is why the pair is atomic
 * and why every case below runs for manga and for novels rather than being maintained twice.
 */
class AddToGroupConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a failed favorite write leaves the entry out of the group`(probe: GroupAddProbe) = runTest {
        probe.addToGroup(favoriteWriteSucceeds = false) shouldBe
            GroupAddEffects(seeded = null, merged = false, favoriteWritten = true, filedCategories = null)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an already-favorited row is merged without rewriting its favorite`(probe: GroupAddProbe) = runTest {
        probe.addToGroup(alreadyFavorite = true) shouldBe
            GroupAddEffects(seeded = false, merged = true, favoriteWritten = false, filedCategories = null)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a row that is gone is not merged`(probe: GroupAddProbe) = runTest {
        probe.addToGroup(rowExists = false) shouldBe
            GroupAddEffects(seeded = null, merged = false, favoriteWritten = false, filedCategories = null)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the group's categories are seeded onto the new member`(probe: GroupAddProbe) = runTest {
        probe.addToGroup(groupCategories = listOf(category(3L))) shouldBe
            GroupAddEffects(seeded = true, merged = true, favoriteWritten = true, filedCategories = listOf(3L))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an uncategorized group reports back so the caller can fall back to its own prompt`(
        probe: GroupAddProbe,
    ) = runTest {
        probe.addToGroup().seeded shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a configured default category files the entry without prompting`(probe: GroupAddProbe) = runTest {
        probe.addToExistingGroup(userCategories = listOf(category(3L)), defaultCategoryId = 3) shouldBe
            GroupAddOutcome.Added(filedCategories = listOf(3L))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `no usable default hands the picker back to the caller`(probe: GroupAddProbe) = runTest {
        probe.addToExistingGroup(userCategories = listOf(category(3L)), defaultCategoryId = -1) shouldBe
            GroupAddOutcome.Prompt(listOf(category(3L)))
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaGroupAddProbe(), NovelGroupAddProbe())
    }
}

private fun category(id: Long) = Category(id = id, name = "category $id", order = 0L, flags = 0L)

/**
 * The stored-row confirm, which had no case on either side and where the two halves disagreed: one
 * trusted the snapshot it was handed and the other re-read the row.
 */
class ConfirmAddCategoriesConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a confirm favorites the row it files into`(probe: GroupAddProbe) = runTest {
        probe.confirmAddCategories(listOf(3L)).favoriteWritten shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a confirm on a row already in the library does not re-favorite it`(probe: GroupAddProbe) = runTest {
        probe.confirmAddCategories(listOf(3L), alreadyFavorite = true).favoriteWritten shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a confirm on a row that has gone writes nothing`(probe: GroupAddProbe) = runTest {
        probe.confirmAddCategories(listOf(3L), rowExists = false).filedCategories shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the system category is never filed`(probe: GroupAddProbe) = runTest {
        probe.confirmAddCategories(listOf(Category.UNCATEGORIZED_ID, 3L)).filedCategories shouldBe listOf(3L)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaGroupAddProbe(), NovelGroupAddProbe())
    }
}

/** What one add-to-group attempt wrote, in terms both content types answer the same way. */
data class GroupAddEffects(
    /** True when the group's categories were seeded, false when it had none, null when nothing ran. */
    val seeded: Boolean?,
    val merged: Boolean,
    val favoriteWritten: Boolean,
    val filedCategories: List<Long>?,
)

/** How an add-to-group with an uncategorized group ended. */
sealed interface GroupAddOutcome {
    data class Added(val filedCategories: List<Long>?) : GroupAddOutcome
    data class Prompt(val categories: List<Category>) : GroupAddOutcome
}

/** One content type's half of the shared cases. */
interface GroupAddProbe {
    suspend fun addToGroup(
        favoriteWriteSucceeds: Boolean = true,
        alreadyFavorite: Boolean = false,
        rowExists: Boolean = true,
        groupCategories: List<Category> = emptyList(),
    ): GroupAddEffects

    suspend fun addToExistingGroup(userCategories: List<Category>, defaultCategoryId: Int): GroupAddOutcome

    /** What a stored row's picker confirm wrote. Both types favorite here, then file. */
    suspend fun confirmAddCategories(
        categoryIds: List<Long>,
        rowExists: Boolean = true,
        alreadyFavorite: Boolean = false,
    ): GroupAddEffects
}

class MangaGroupAddProbe : GroupAddProbe {

    private var merged = false
    private var favoriteWritten = false
    private var filed: List<Long>? = null

    override fun toString() = "manga"

    private val manga = mockk<Manga> {
        every { id } returns 1L
        every { source } returns 99L
    }

    private fun adder(
        favoriteWriteSucceeds: Boolean,
        alreadyFavorite: Boolean,
        rowExists: Boolean,
        groupCategories: List<Category>,
        userCategories: List<Category>,
        defaultCategoryId: Int,
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
        setMangaCategories = mockk<SetMangaCategories> {
            coEvery { await(any(), any()) } answers { filed = secondArg() }
        },
        setMangaDefaultChapterFlags = mockk(relaxed = true),
        updateManga = mockk {
            coEvery { awaitUpdateFavorite(any(), any()) } answers {
                favoriteWritten = true
                favoriteWriteSucceeds
            }
        },
        addTracks = mockk(relaxed = true),
        mergeManager = mockk<MangaMergeManager>(relaxed = true) {
            coEvery { merge(any()) } answers { merged = true }
        },
        transactions = PassThroughTransactions,
        reikaiLibraryPreferences = mockk {
            every { categorySortOrder } returns mockk { every { get() } returns 0 }
        },
    )

    private fun reset() {
        merged = false
        favoriteWritten = false
        filed = null
    }

    override suspend fun addToGroup(
        favoriteWriteSucceeds: Boolean,
        alreadyFavorite: Boolean,
        rowExists: Boolean,
        groupCategories: List<Category>,
    ): GroupAddEffects {
        reset()
        val seeded = adder(favoriteWriteSucceeds, alreadyFavorite, rowExists, groupCategories, emptyList(), -1)
            .addToGroup(manga, listOf(2L))
        return GroupAddEffects(seeded, merged, favoriteWritten, filed)
    }

    override suspend fun addToExistingGroup(
        userCategories: List<Category>,
        defaultCategoryId: Int,
    ): GroupAddOutcome {
        reset()
        val result = adder(true, false, true, emptyList(), userCategories, defaultCategoryId)
            .addToExistingGroup(manga, listOf(2L))
        return when (result) {
            is AddFavoriteResult.NeedsCategoryChoice -> GroupAddOutcome.Prompt(result.initialSelection.map { it.value })
            else -> GroupAddOutcome.Added(filed)
        }
    }

    override suspend fun confirmAddCategories(
        categoryIds: List<Long>,
        rowExists: Boolean,
        alreadyFavorite: Boolean,
    ): GroupAddEffects {
        reset()
        adder(true, alreadyFavorite, rowExists, emptyList(), emptyList(), -1)
            .confirmAddCategories(mangaId = 1L, categoryIds = categoryIds)
        return GroupAddEffects(
            seeded = null,
            merged = merged,
            favoriteWritten = favoriteWritten,
            filedCategories = filed,
        )
    }
}

class NovelGroupAddProbe : GroupAddProbe {

    private var merged = false
    private var favoriteWritten = false
    private var filed: List<Long>? = null

    override fun toString() = "novel"

    private val item = NovelItem(name = "a novel", path = "/a-novel", cover = null)

    private fun adder(
        favoriteWriteSucceeds: Boolean,
        alreadyFavorite: Boolean,
        rowExists: Boolean,
        groupCategories: List<Category>,
        userCategories: List<Category>,
        defaultCategoryId: Int,
    ) = NovelLibraryAdder(
        novelRepository = mockk<NovelRepository> {
            coEvery { getById(any()) } returns
                if (rowExists) mockk<Novel> { every { favorite } returns alreadyFavorite } else null
            coEvery { getByUrlAndSource(any(), any()) } returns null
            coEvery { insertOrGet(any()) } returns mockk<Novel> {
                every { id } returns 1L
                every { favorite } returns alreadyFavorite
            }
        },
        manager = mockk(relaxed = true),
        getNovelCategories = mockk<GetNovelCategories> {
            coEvery { await() } returns userCategories
            coEvery { awaitByNovelId(any()) } returns groupCategories
        },
        setNovelCategories = mockk {
            coEvery { await(any(), any()) } answers { filed = secondArg() }
        },
        updateNovel = mockk {
            coEvery { awaitUpdateFavorite(any(), any()) } answers {
                favoriteWritten = true
                favoriteWriteSucceeds
            }
        },
        novelPreferences = mockk(relaxed = true) {
            every { defaultNovelCategory() } returns mockk { every { get() } returns defaultCategoryId }
        },
        mergeManager = mockk<NovelMergeManager>(relaxed = true) {
            coEvery { merge(any()) } answers { merged = true }
        },
        transactions = PassThroughTransactions,
        reikaiLibraryPreferences = mockk {
            every { categorySortOrder } returns mockk { every { get() } returns 0 }
        },
    )

    private fun reset() {
        merged = false
        favoriteWritten = false
        filed = null
    }

    override suspend fun addToGroup(
        favoriteWriteSucceeds: Boolean,
        alreadyFavorite: Boolean,
        rowExists: Boolean,
        groupCategories: List<Category>,
    ): GroupAddEffects {
        reset()
        val seeded = adder(favoriteWriteSucceeds, alreadyFavorite, rowExists, groupCategories, emptyList(), -1)
            .addToGroup(1L, listOf(2L))
        return GroupAddEffects(seeded, merged, favoriteWritten, filed)
    }

    override suspend fun addToExistingGroup(
        userCategories: List<Category>,
        defaultCategoryId: Int,
    ): GroupAddOutcome {
        reset()
        val dialog = adder(true, false, true, emptyList(), userCategories, defaultCategoryId)
            .addToExistingGroup(item, sourceId = "src", selectedIds = listOf(2L))
        return when (dialog) {
            is NovelBrowseDialog.ChangeCategory -> GroupAddOutcome.Prompt(dialog.initialSelection.map { it.value })
            else -> GroupAddOutcome.Added(filed)
        }
    }

    override suspend fun confirmAddCategories(
        categoryIds: List<Long>,
        rowExists: Boolean,
        alreadyFavorite: Boolean,
    ): GroupAddEffects {
        reset()
        adder(true, alreadyFavorite, rowExists, emptyList(), emptyList(), -1)
            .confirmAddCategories(novelId = 1L, categoryIds = categoryIds)
        return GroupAddEffects(
            seeded = null,
            merged = merged,
            favoriteWritten = favoriteWritten,
            filedCategories = filed,
        )
    }
}
