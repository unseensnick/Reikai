package reikai.presentation.library

import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test
import tachiyomi.domain.category.model.Category

class ReikaiCategorySortTest {

    private val system = Category(id = 0, name = "", order = -1, flags = 0)
    private val alpha = Category(id = 1, name = "alpha", order = 2, flags = 0)
    private val beta = Category(id = 2, name = "Beta", order = 1, flags = 0)

    @Test
    fun `manual order follows each category's order, not the list's`() {
        reikaiSortCategories(listOf(alpha, system, beta), sortOrder = 0) shouldContainExactly
            listOf(system, beta, alpha)
    }

    @Test
    fun `A to Z keeps the system category on top`() {
        reikaiSortCategories(listOf(beta, alpha, system), sortOrder = 1) shouldContainExactly
            listOf(system, alpha, beta)
    }

    @Test
    fun `Z to A keeps the system category on top`() {
        reikaiSortCategories(listOf(alpha, system, beta), sortOrder = 2) shouldContainExactly
            listOf(system, beta, alpha)
    }
}
