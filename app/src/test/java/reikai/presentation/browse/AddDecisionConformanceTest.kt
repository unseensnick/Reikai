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
import reikai.presentation.novel.browse.NovelDuplicateInfo
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.MangaWithChapterCount

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
    fun `an entry already in the library is offered for removal`(probe: AddDecisionProbe) = runTest {
        decideAdd(inLibrary = true) { probe.duplicatePayload() } shouldBe AddDecision.Remove
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry already in the library is never looked up for duplicates`(probe: AddDecisionProbe) = runTest {
        var lookups = 0
        decideAdd(inLibrary = true) {
            lookups++
            probe.duplicatePayload()
        }

        lookups shouldBe 0
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a possible duplicate asks before adding`(probe: AddDecisionProbe) = runTest {
        val payload = probe.duplicatePayload()

        decideAdd(inLibrary = false) { payload } shouldBe AddDecision.ConfirmDuplicate(payload)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `nothing similar adds outright`(probe: AddDecisionProbe) = runTest {
        decideAdd(inLibrary = false) { probe.noDuplicates() } shouldBe AddDecision.Add
    }

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

    /** This type's duplicate payload, as its own adder would hand it to a dialog. */
    fun duplicatePayload(): Any

    /** What this type's lookup answers when nothing similar is in the library. */
    fun noDuplicates(): Any?
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

    /** Manga hands the dialog the rows themselves, so an empty list is what "none" looks like. */
    override fun duplicatePayload(): Any = listOf(mockk<MangaWithChapterCount>())

    override fun noDuplicates(): Any? = emptyList<MangaWithChapterCount>().takeIf { it.isNotEmpty() }
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

    /** Novels hand the dialog resolved source names beside the rows, so "none" is a null payload. */
    override fun duplicatePayload(): Any =
        NovelDuplicateInfo(listOf(mockk()), sourceNames = emptyMap(), sourceSites = emptyMap())

    override fun noDuplicates(): Any? = null
}
