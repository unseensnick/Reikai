package reikai.presentation.browse

import eu.kanade.tachiyomi.source.Source
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
import reikai.domain.merge.EntryMergeManager
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelWithChapterCount
import reikai.novel.source.NovelSource
import reikai.presentation.browse.components.EntrySourceLabel
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.domain.category.interactor.SetMangaCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.manga.model.Manga
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
        decideAdd(inLibrary = true) { probe.prompt(duplicate = true) } shouldBe AddDecision.Remove
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an entry already in the library is never looked up for duplicates`(probe: AddDecisionProbe) = runTest {
        var lookups = 0
        decideAdd(inLibrary = true) {
            lookups++
            probe.prompt(duplicate = true)
        }

        lookups shouldBe 0
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a possible duplicate asks before adding`(probe: AddDecisionProbe) = runTest {
        val prompt = probe.prompt(duplicate = true)

        decideAdd(inLibrary = false) { prompt } shouldBe AddDecision.ConfirmDuplicate(prompt!!)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `nothing similar adds outright`(probe: AddDecisionProbe) = runTest {
        decideAdd(inLibrary = false) { probe.prompt(duplicate = false) } shouldBe AddDecision.Add
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `nothing similar raises no prompt`(probe: AddDecisionProbe) = runTest {
        probe.prompt(duplicate = false) shouldBe null
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a duplicate's prompt carries the group it belongs to`(probe: AddDecisionProbe) = runTest {
        probe.prompt(duplicate = true)?.groupIdByEntryId shouldBe mapOf(DUPLICATE_ID to GROUP_ID)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a duplicate's prompt offers grouping when the merge manager does`(probe: AddDecisionProbe) = runTest {
        probe.prompt(duplicate = true)?.suggestGroup shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a duplicate's prompt names its source`(probe: AddDecisionProbe) = runTest {
        probe.prompt(duplicate = true)?.sourceLabels?.values?.toList() shouldBe
            listOf(EntrySourceLabel.Installed(SOURCE_NAME))
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the picker lists categories in the sort-order preference, not table order`(
        probe: AddDecisionProbe,
    ) = runTest {
        probe.picker(
            userCategories = listOf(category(3L, "Zeta"), category(4L, "Alpha")),
            current = emptyList(),
            sortOrder = 1,
        ) shouldBe listOf(4L to false, 3L to false)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaAddDecisionProbe(), NovelAddDecisionProbe())
    }
}

private const val DUPLICATE_ID = 7L
private const val GROUP_ID = 70L
private const val SOURCE_NAME = "Home"

/** A merge manager that has [DUPLICATE_ID] grouped and offers grouping on add. */
private inline fun <reified T : EntryMergeManager> groupingMergeManager(): T = mockk(relaxed = true) {
    coEvery { groupIdsFor(listOf(DUPLICATE_ID)) } returns mapOf(DUPLICATE_ID to GROUP_ID)
    every { suggestGroupingOnAdd } returns true
}

private fun category(id: Long, name: String = "category $id") =
    Category(id = id, name = name, order = 0L, flags = 0L)

/** What resolving answered, plus whether it wrote while answering. */
data class Resolution(val categoryIds: List<Long>?, val wroteCategories: Boolean)

/** One content type's half of the shared cases, normalized so both answer in the same shape. */
interface AddDecisionProbe {
    suspend fun resolve(userCategories: List<Category>, defaultId: Int): Resolution

    /** The picker's initial state as (category id, checked), under a category sort-order preference. */
    suspend fun picker(
        userCategories: List<Category>,
        current: List<Category>,
        sortOrder: Int = 0,
    ): List<Pair<Long, Boolean>>

    /** The prompt this type's adder raises with one possible duplicate in the library, or with none. */
    suspend fun prompt(duplicate: Boolean): DuplicatePrompt<*, *>?
}

class MangaAddDecisionProbe : AddDecisionProbe {

    private var wroteCategories = false

    override fun toString() = "manga"

    private fun adder(
        userCategories: List<Category>,
        defaultId: Int,
        current: List<Category>,
        sortOrder: Int = 0,
        duplicates: List<MangaWithChapterCount> = emptyList(),
    ) =
        MangaLibraryAdder(
            sourceManager = mockk {
                coEvery { getOrStub(DUPLICATE_SOURCE) } returns mockk<Source> { every { name } returns SOURCE_NAME }
            },
            coverCache = mockk(relaxed = true),
            libraryPreferences = mockk(relaxed = true) {
                every { defaultCategory } returns mockk { every { get() } returns defaultId }
            },
            getCategories = mockk {
                every { subscribe() } returns flowOf(userCategories)
                coEvery { await(any<Long>()) } returns current
            },
            getDuplicateLibraryManga = mockk { coEvery { this@mockk.invoke(any()) } returns duplicates },
            getManga = mockk(relaxed = true),
            setMangaCategories = mockk<SetMangaCategories> {
                coEvery { await(any(), any()) } answers { wroteCategories = true }
            },
            setMangaDefaultChapterFlags = mockk(relaxed = true),
            updateManga = mockk(relaxed = true),
            autoBindOnAdd = mockk(relaxed = true),
            mergeManager = groupingMergeManager<MangaMergeManager>(),
            transactions = PassThroughTransactions,
            reikaiLibraryPreferences = mockk {
                every { categorySortOrder } returns mockk { every { get() } returns sortOrder }
            },
            sourceTracker = mockk(relaxed = true),
        )

    override suspend fun resolve(userCategories: List<Category>, defaultId: Int): Resolution {
        wroteCategories = false
        val ids = adder(userCategories, defaultId, current = emptyList()).resolveDefaultCategories()
        return Resolution(ids, wroteCategories)
    }

    override suspend fun picker(userCategories: List<Category>, current: List<Category>, sortOrder: Int) =
        adder(userCategories, defaultId = -1, current = current, sortOrder = sortOrder)
            .categoryPickerSelection(mangaId = 1L)
            .map { it.value.id to (it is CheckboxState.State.Checked) }

    override suspend fun prompt(duplicate: Boolean): DuplicatePrompt<*, *>? {
        val row = MangaWithChapterCount(Manga.create().copy(id = DUPLICATE_ID, source = DUPLICATE_SOURCE), 0L)
        return adder(emptyList(), -1, emptyList(), duplicates = listOfNotNull(row.takeIf { duplicate }))
            .findDuplicates(Manga.create())
    }

    private companion object {
        const val DUPLICATE_SOURCE = 5L
    }
}

class NovelAddDecisionProbe : AddDecisionProbe {

    private var wroteCategories = false

    override fun toString() = "novel"

    private fun adder(
        userCategories: List<Category>,
        defaultId: Int,
        current: List<Category>,
        sortOrder: Int = 0,
        duplicates: List<NovelWithChapterCount> = emptyList(),
    ) =
        NovelLibraryAdder(
            novelRepository = mockk(relaxed = true) {
                coEvery { getDuplicateLibraryNovel(any(), any()) } returns duplicates
            },
            manager = mockk {
                coEvery { this@mockk.get(DUPLICATE_SOURCE) } returns
                    mockk<NovelSource> { every { name } returns SOURCE_NAME }
            },
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
            mergeManager = groupingMergeManager<NovelMergeManager>(),
            transactions = PassThroughTransactions,
            reikaiLibraryPreferences = mockk {
                every { categorySortOrder } returns mockk { every { get() } returns sortOrder }
            },
            autoBindOnAdd = mockk(relaxed = true),
            removeNovelsFromLibrary = mockk(relaxed = true),
        )

    override suspend fun resolve(userCategories: List<Category>, defaultId: Int): Resolution {
        wroteCategories = false
        val ids = adder(userCategories, defaultId, current = emptyList()).resolveDefaultCategories()
        return Resolution(ids, wroteCategories)
    }

    override suspend fun picker(userCategories: List<Category>, current: List<Category>, sortOrder: Int) =
        adder(userCategories, defaultId = -1, current = current, sortOrder = sortOrder)
            .categoryPickerPrompt(novelId = 1L)
            .map { it.value.id to it.isChecked }

    override suspend fun prompt(duplicate: Boolean): DuplicatePrompt<*, *>? {
        val row = NovelWithChapterCount(Novel.create().copy(id = DUPLICATE_ID, source = DUPLICATE_SOURCE), 0L)
        return adder(emptyList(), -1, emptyList(), duplicates = listOfNotNull(row.takeIf { duplicate }))
            .findDuplicates(-1L, "Title")
    }

    private companion object {
        const val DUPLICATE_SOURCE = "home"
    }
}
