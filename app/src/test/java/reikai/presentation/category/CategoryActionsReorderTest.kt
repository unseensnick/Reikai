package reikai.presentation.category

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.category.CategoryContentType
import reikai.domain.category.categoriesForContentType
import reikai.domain.library.ContentType
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * The screen sends a drop index into the list its chip shows, while one order column serves both
 * libraries, so a move under a chip has to land among the shown rows and leave every hidden row in its
 * slot. The move menu asks for index 0 or past the end through All, which are the whole table's ends.
 */
class CategoryActionsReorderTest {

    private val m1 = 11L
    private val u1 = 12L
    private val n1 = 13L
    private val m2 = 14L
    private val n2 = 15L

    // Stored order follows each write, as the database's does, so consecutive moves read the last one.
    private var table = listOf(
        Category(id = 0L, name = "", order = -1L, flags = 0L),
        category(m1, 0, CategoryContentType.MANGA),
        category(u1, 1, CategoryContentType.UNIVERSAL),
        category(n1, 2, CategoryContentType.NOVEL),
        category(m2, 3, CategoryContentType.MANGA),
        category(n2, 4, CategoryContentType.NOVEL),
    )
    private val repository = mockk<CategoryRepository> {
        coEvery { getUnfiltered() } answers { table }
        coEvery { updateAllOrders(any()) } answers {
            val ids = firstArg<List<Long>>()
            table = table
                .map { row -> if (row.id == 0L) row else row.copy(order = ids.indexOf(row.id).toLong()) }
                .sortedBy { it.order }
        }
    }
    private val actions = CategoryActions(repository, mockk(), mockk(), mockk())

    @Test
    fun `a move to the top puts the category first`() = runTest {
        actions.reorder(row(n2), 0, ContentType.ALL)

        storedIds() shouldBe listOf(n2, m1, u1, n1, m2)
    }

    @Test
    fun `a move past the end puts the category last`() = runTest {
        actions.reorder(row(m1), Int.MAX_VALUE, ContentType.ALL)

        storedIds() shouldBe listOf(u1, n1, m2, n2, m1)
    }

    @Test
    fun `a drag to the top under Novels takes the first shown row's slot`() = runTest {
        actions.reorder(row(n2), 0, ContentType.NOVELS)

        storedIds() shouldBe listOf(m1, n2, u1, m2, n1)
    }

    @Test
    fun `a drag under Novels shows the row where it was dropped`() = runTest {
        actions.reorder(row(n2), 0, ContentType.NOVELS)

        shownIds(ContentType.NOVELS) shouldBe listOf(n2, u1, n1)
    }

    @Test
    fun `a drag under Manga leaves every novel row in its slot`() = runTest {
        actions.reorder(row(m1), 2, ContentType.MANGA)

        storedIds() shouldBe listOf(u1, m2, n1, m1, n2)
    }

    @Test
    fun `a universal row dragged under Novels moves only among the rows shown there`() = runTest {
        actions.reorder(row(u1), Int.MAX_VALUE, ContentType.NOVELS)

        storedIds() shouldBe listOf(m1, n1, n2, m2, u1)
    }

    /** The drag library reports one single-step swap per row crossed, each a separate reorder. */
    @Test
    fun `a step-by-step drag under Novels ends where it was dropped`() = runTest {
        actions.reorder(row(n2), 1, ContentType.NOVELS)
        actions.reorder(row(n2), 0, ContentType.NOVELS)

        storedIds() shouldBe listOf(m1, n2, u1, m2, n1)
    }

    @Test
    fun `a step-by-step drag under Manga ends where it was dropped`() = runTest {
        actions.reorder(row(m2), 1, ContentType.MANGA)
        actions.reorder(row(m2), 0, ContentType.MANGA)

        storedIds() shouldBe listOf(m2, m1, n1, u1, n2)
    }

    private fun category(id: Long, order: Long, type: Long) =
        Category(id = id, name = "c$id", order = order, flags = 0L, contentType = type)

    private fun row(id: Long) = table.first { it.id == id }

    private fun storedIds() = table.filterNot(Category::isSystemCategory).sortedBy { it.order }.map { it.id }

    private fun shownIds(type: ContentType) =
        categoriesForContentType(table.filterNot(Category::isSystemCategory).sortedBy { it.order }, type).map { it.id }
}
