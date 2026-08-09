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
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category

/**
 * The add flow's decision half, pinned once for both content types instead of as a twin pair. The
 * decision has to stay a read: a caller favorites between deciding and filing, and only that ordering
 * leaves nothing behind when the favorite write fails. Each probe drives one content type's adder and
 * the cases are shared, so neither type can answer differently without a red test.
 * Background: docs/dev/plans/content-layer-add-flow.md.
 */
class AddDecisionConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `resolving the default category writes nothing`(probe: AddDecisionProbe) = runTest {
        probe.resolve(userCategories = listOf(category(3L)), defaultId = 3) shouldBe
            Resolution(categoryIds = listOf(3L), wroteCategories = false)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `no usable default resolves to null so the caller prompts`(probe: AddDecisionProbe) = runTest {
        probe.resolve(userCategories = listOf(category(3L)), defaultId = -1) shouldBe
            Resolution(categoryIds = null, wroteCategories = false)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the picker starts with the entry's current categories checked`(probe: AddDecisionProbe) = runTest {
        probe.picker(userCategories = listOf(category(3L), category(4L)), current = listOf(category(3L))) shouldBe
            listOf(3L to true, 4L to false)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaAddDecisionProbe(), NovelAddDecisionProbe())
    }
}

private fun category(id: Long) = Category(id = id, name = "category $id", order = 0L, flags = 0L)

/** What resolving answered, plus whether it wrote while answering. */
data class Resolution(val categoryIds: List<Long>?, val wroteCategories: Boolean)

/** One content type's half of the shared cases, normalized so both answer in the same shape. */
interface AddDecisionProbe {
    suspend fun resolve(userCategories: List<Category>, defaultId: Int): Resolution

    /** The picker's initial state as (category id, checked). */
    suspend fun picker(userCategories: List<Category>, current: List<Category>): List<Pair<Long, Boolean>>
}

class MangaAddDecisionProbe : AddDecisionProbe {

    private var wroteCategories = false

    override fun toString() = "manga"

    private fun adder(userCategories: List<Category>, defaultId: Int, current: List<Category>) =
        MangaLibraryAdder(
            sourceManager = mockk(relaxed = true),
            coverCache = mockk(relaxed = true),
            libraryPreferences = mockk(relaxed = true) {
                every { defaultCategory } returns mockk { every { get() } returns defaultId }
            },
            getCategories = mockk {
                every { subscribe() } returns flowOf(userCategories)
                coEvery { await(any<Long>()) } returns current
            },
            getDuplicateLibraryManga = mockk(relaxed = true),
            getManga = mockk(relaxed = true),
            setMangaCategories = mockk<SetMangaCategories> {
                coEvery { await(any(), any()) } answers { wroteCategories = true }
            },
            setMangaDefaultChapterFlags = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            addTracks = mockk(relaxed = true),
            mergeManager = mockk(relaxed = true),
            transactions = PassThroughTransactions,
        )

    override suspend fun resolve(userCategories: List<Category>, defaultId: Int): Resolution {
        wroteCategories = false
        val ids = adder(userCategories, defaultId, current = emptyList()).resolveDefaultCategories()
        return Resolution(ids, wroteCategories)
    }

    override suspend fun picker(userCategories: List<Category>, current: List<Category>) =
        adder(userCategories, defaultId = -1, current = current)
            .categoryPickerSelection(mangaId = 1L)
            .map { it.value.id to (it is CheckboxState.State.Checked) }
}

class NovelAddDecisionProbe : AddDecisionProbe {

    private var wroteCategories = false

    override fun toString() = "novel"

    private fun adder(userCategories: List<Category>, defaultId: Int, current: List<Category>) =
        NovelLibraryAdder(
            novelRepository = mockk(relaxed = true),
            manager = mockk(relaxed = true),
            getNovelCategories = mockk<GetNovelCategories> {
                coEvery { await() } returns userCategories
                coEvery { awaitByNovelId(any()) } returns current
            },
            setNovelCategories = mockk {
                coEvery { await(any(), any()) } answers { wroteCategories = true }
            },
            updateNovel = mockk(relaxed = true),
            novelPreferences = mockk(relaxed = true) {
                every { defaultNovelCategory() } returns mockk { every { get() } returns defaultId }
            },
            mergeManager = mockk(relaxed = true),
            transactions = PassThroughTransactions,
        )

    override suspend fun resolve(userCategories: List<Category>, defaultId: Int): Resolution {
        wroteCategories = false
        val ids = adder(userCategories, defaultId, current = emptyList()).resolveDefaultCategories()
        return Resolution(ids, wroteCategories)
    }

    override suspend fun picker(userCategories: List<Category>, current: List<Category>) =
        adder(userCategories, defaultId = -1, current = current)
            .categoryPickerPrompt(novelId = 1L)
            .let { prompt -> prompt.categories.map { it.id to (it.id in prompt.currentIds) } }
}
