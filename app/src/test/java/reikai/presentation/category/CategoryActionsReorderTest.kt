package reikai.presentation.category

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.category.repository.CategoryRepository

/**
 * The categories screen's move menu asks for index 0 or past the end rather than knowing the list's
 * size, so the reorder has to land those on the two ends of the whole list.
 */
class CategoryActionsReorderTest {

    private val categories = listOf(
        Category(id = 0L, name = "", order = -1L, flags = 0L),
        Category(id = 1L, name = "A", order = 0L, flags = 0L),
        Category(id = 2L, name = "B", order = 1L, flags = 0L),
        Category(id = 3L, name = "C", order = 2L, flags = 0L),
    )
    private val written = slot<List<Long>>()
    private val repository = mockk<CategoryRepository> {
        coEvery { getUnfiltered() } returns categories
        coEvery { updateAllOrders(capture(written)) } returns Unit
    }
    private val actions = CategoryActions(repository, mockk(), mockk(), mockk())

    @Test
    fun `a move to the top puts the category first`() = runTest {
        actions.reorder(categories[3], 0)

        written.captured shouldBe listOf(3L, 1L, 2L)
    }

    @Test
    fun `a move past the end puts the category last`() = runTest {
        actions.reorder(categories[1], Int.MAX_VALUE)

        written.captured shouldBe listOf(2L, 3L, 1L)
    }
}
