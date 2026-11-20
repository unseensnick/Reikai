package eu.kanade.tachiyomi.ui.category

import androidx.lifecycle.ViewModelStore
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.category.CategoryContentType
import reikai.domain.category.CategoryIdPreferences
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.MainDispatcherExtension
import reikai.presentation.category.CategoryActions
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.domain.upcoming.service.UpcomingPreferences

class CategoryViewModelTest {

    private val a = Category(id = 1L, name = "A", order = 0L, flags = 0L)
    private val b = Category(id = 2L, name = "B", order = 1L, flags = 0L)
    private val c = Category(id = 3L, name = "C", order = 2L, flags = 0L)

    private val novel = Category(id = 4L, name = "N", order = 3L, flags = 0L, contentType = CategoryContentType.NOVEL)

    private val table = MutableStateFlow(listOf(a, b, c))
    private val repository = mockk<CategoryRepository>(relaxed = true) {
        every { getUnfilteredAsFlow() } returns table
        coEvery { getUnfiltered() } answers { table.value }
        coEvery { delete(any()) } answers { table.update { rows -> rows.filterNot { it.id == firstArg<Long>() } } }
    }

    private val store = EmittingPreferenceStore()
    private val libraryPreferences = LibraryPreferences(store)
    private val reikaiLibraryPreferences = ReikaiLibraryPreferences(store)
    private val upcomingPreferences = UpcomingPreferences(store)
    private val novelPreferences = NovelPreferences(store)
    private val actions = CategoryActions(
        categoryRepository = repository,
        categoryIdPreferences = CategoryIdPreferences(
            libraryPreferences,
            DownloadPreferences(store),
            novelPreferences,
            reikaiLibraryPreferences,
            ReikaiSourcePreferences(store),
            upcomingPreferences,
        ),
        libraryPreferences = libraryPreferences,
        renameCategory = mockk(),
    )

    // Every model is tracked, so its state's stop timeout is cancelled before Main is reset.
    @JvmField
    @RegisterExtension
    val main = MainDispatcherExtension()

    /** A model whose state is collected, as the screen collects it. */
    private fun TestScope.model(): CategoryViewModel {
        // Unconfined, so a committed delete has landed by the time the call returns
        val appScope = backgroundScope + UnconfinedTestDispatcher(testScheduler)
        val model = main.track(CategoryViewModel(appScope, actions, reikaiLibraryPreferences))
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.state.collect {} }
        return model
    }

    private val CategoryViewModel.selection
        get() = (state.value as CategoryScreenState.Success).selection

    /** The chip row hides itself then, so a chip left on Novels must not keep the list on novels. */
    @Test
    fun `hiding the chip's type while the screen is open follows the Show setting`() = runTest {
        table.value += novel
        val model = model()
        model.setContentType(ContentType.NOVELS)

        reikaiLibraryPreferences.shownContent.set(ContentType.MANGA)

        (model.state.value as CategoryScreenState.Success).categories shouldBe listOf(a, b, c)
    }

    @Test
    fun `the list narrows to the shown type while the other is hidden`() = runTest {
        table.value += novel
        reikaiLibraryPreferences.shownContent.set(ContentType.NOVELS)

        val state = model().state.first { it is CategoryScreenState.Success } as CategoryScreenState.Success

        (state.contentType to state.categories) shouldBe (ContentType.NOVELS to listOf(novel))
    }

    @Test
    fun `a name only a hidden category holds stays free`() = runTest {
        table.value += novel
        reikaiLibraryPreferences.shownContent.set(ContentType.NOVELS)

        val state = model().state.first { it is CategoryScreenState.Success } as CategoryScreenState.Success

        state.allNames shouldBe listOf("N")
    }

    @Test
    fun `every name counts while both types show, so a chip cannot let a duplicate through`() = runTest {
        table.value += novel

        val state = model().state.first { it is CategoryScreenState.Success } as CategoryScreenState.Success

        state.allNames shouldBe listOf("A", "B", "C", "N")
    }

    /** The screen only masks picks the chip hides, so kept ones came back on the next flip. */
    @Test
    fun `flipping the chip drops the picks it would hide`() = runTest {
        val model = model()
        model.toggleSelection(a.id)
        model.setContentType(ContentType.NOVELS)

        model.setContentType(ContentType.ALL)

        model.selection shouldBe emptySet()
    }

    @Test
    fun `re-selecting the chip shown keeps the selection`() = runTest {
        val model = model()
        model.toggleSelection(a.id)

        model.setContentType(ContentType.ALL)

        model.selection shouldBe setOf(a.id)
    }

    /** The long press ranges from the selection's anchor, so a delete that cleared only what the screen
     *  shows left the deleted rows to come back with it. */
    @Test
    fun `a long press after undoing a bulk delete selects only the pressed row`() = runTest {
        val model = model()
        model.toggleSelection(a.id)
        model.toggleRangeSelection(b.id)
        model.deleteSelected()
        model.undoPendingDelete(model.undoBatches().single())

        model.toggleRangeSelection(c.id)

        model.selection shouldBe setOf(c.id)
    }

    /** The screen shows one snackbar per delete, one after another, so each must resolve only its own rows. */
    @Test
    fun `undoing the first delete leaves the second to its own snackbar`() = runTest {
        val model = model()
        model.deleteCategory(a)
        model.deleteCategory(b)
        val (first, second) = model.undoBatches()

        model.undoPendingDelete(first)
        model.commitPendingDelete(second)

        table.value shouldBe listOf(a, c)
    }

    @Test
    fun `committing the first delete leaves the second undoable`() = runTest {
        val model = model()
        model.deleteCategory(a)
        model.deleteCategory(b)
        val (first, second) = model.undoBatches()

        model.commitPendingDelete(first)
        model.undoPendingDelete(second)

        table.value shouldBe listOf(b, c)
    }

    @Test
    fun `committing a delete drops the category from the collapsed ones`() = runTest {
        val model = model()
        reikaiLibraryPreferences.collapsedCategories.set(setOf(a.id.toString(), b.id.toString()))
        model.deleteCategory(a)

        model.commitPendingDelete(model.undoBatches().single())

        reikaiLibraryPreferences.collapsedCategories.get() shouldBe setOf(b.id.toString())
    }

    @Test
    fun `committing a delete drops the category from the Upcoming filter`() = runTest {
        val model = model()
        upcomingPreferences.filterIncludedCategories.set(listOf(a.id, b.id))
        model.deleteCategory(a)

        model.commitPendingDelete(model.undoBatches().single())

        upcomingPreferences.filterIncludedCategories.get() shouldBe listOf(b.id)
    }

    /** Leaving the screen pops and clears its model, after the model's own scope is gone. */
    @Test
    fun `clearing the model commits its pending delete`() = runTest {
        val model = model()
        model.deleteCategory(a)

        ViewModelStore().apply { put("categories", model) }.clear()

        table.value shouldBe listOf(b, c)
    }

    /** The snackbar is drawn from the undoable deletes, so this is what takes it off screen. */
    @Test
    fun `a delete a drag has started committing is no longer offered for undo`() = runTest {
        coEvery { repository.delete(any()) } coAnswers { awaitCancellation() }
        val model = model()
        model.deleteCategory(a)

        model.changeOrder(b, 0)

        model.undoBatches() shouldBe emptyList()
    }

    @ParameterizedTest
    @MethodSource("ownTypePreferences")
    fun `committing a delete scrubs the id from its own type's preferences only`(
        contentType: Long,
        kept: Set<String>,
    ) = runTest {
        val row = novel.copy(contentType = contentType)
        table.value += row
        referenceEverywhere(row.id)
        val model = model()
        model.deleteCategory(row)

        model.commitPendingDelete(model.undoBatches().single())

        preferencesHolding(row.id) shouldBe kept
    }

    private fun referenceEverywhere(id: Long) {
        libraryPreferences.updateCategories.set(setOf(id.toString()))
        libraryPreferences.defaultCategory.set(id.toInt())
        novelPreferences.novelUpdateCategories().set(setOf(id.toString()))
        novelPreferences.defaultNovelCategory().set(id.toInt())
    }

    private fun preferencesHolding(id: Long) = mapOf(
        MANGA_SET to (id.toString() in libraryPreferences.updateCategories.get()),
        MANGA_DEFAULT to (libraryPreferences.defaultCategory.get() == id.toInt()),
        NOVEL_SET to (id.toString() in novelPreferences.novelUpdateCategories().get()),
        NOVEL_DEFAULT to (novelPreferences.defaultNovelCategory().get() == id.toInt()),
    ).filterValues { it }.keys

    private val universal =
        Category(id = 5L, name = "U", order = 1L, flags = 0L, contentType = CategoryContentType.UNIVERSAL)

    /** A table of [a, universal, novel, b] whose stored order follows every write, as the database's does. */
    private fun mixedTable() {
        table.value = listOf(a, universal, novel.copy(order = 2L), b.copy(order = 3L))
        coEvery { repository.updateAllOrders(any()) } answers {
            val ids = firstArg<List<Long>>()
            table.update { rows -> rows.map { it.copy(order = ids.indexOf(it.id).toLong()) }.sortedBy { it.order } }
        }
    }

    private fun storedIds() = table.value.sortedBy { it.order }.map { it.id }

    /** The drop index is a slot in the narrowed list on screen, which also hides the row pending delete. */
    @Test
    fun `a drag under a chip with an undo-delete pending lands where it was dropped`() = runTest {
        mixedTable()
        val model = model()
        model.setContentType(ContentType.MANGA)
        model.deleteCategory(universal)

        model.changeOrder(b, 0)

        storedIds() shouldBe listOf(b.id, novel.id, a.id)
    }

    @Test
    fun `a drag while Show hides novels moves among the manga rows`() = runTest {
        mixedTable()
        reikaiLibraryPreferences.shownContent.set(ContentType.MANGA)
        val model = model()

        model.changeOrder(b, 0)

        storedIds() shouldBe listOf(b.id, a.id, novel.id, universal.id)
    }

    /** The move menu means the ends of every category, not of the ones the chip shows. */
    @Test
    fun `move to top under a chip puts the category first in the whole table`() = runTest {
        mixedTable()
        val model = model()
        model.setContentType(ContentType.NOVELS)

        model.moveToEnd(novel, 0)

        storedIds().first() shouldBe novel.id
    }

    private fun CategoryViewModel.undoBatches() =
        (state.value as CategoryScreenState.Success).undoableDeletes.map { it.batch }

    private companion object {
        const val MANGA_SET = "manga update categories"
        const val MANGA_DEFAULT = "manga default category"
        const val NOVEL_SET = "novel update categories"
        const val NOVEL_DEFAULT = "novel default category"

        /** A deleted row's type to the preferences that must still name its id: the other type's. */
        @JvmStatic
        fun ownTypePreferences() = listOf(
            Arguments.of(CategoryContentType.MANGA, setOf(NOVEL_SET, NOVEL_DEFAULT)),
            Arguments.of(CategoryContentType.NOVEL, setOf(MANGA_SET, MANGA_DEFAULT)),
        )
    }
}
