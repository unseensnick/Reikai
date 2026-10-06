package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.CheckboxState

/** The one category decision both bulk adds (browse and Related "See all") take for a whole batch. */
class BatchCategoriesTest {

    private val categories = listOf(libraryCategory(3L), libraryCategory(4L))

    @Test
    fun `a usable default files the whole batch there`() {
        batchCategories(categories, defaultCategoryId = 4) shouldBe BatchCategories.Default(listOf(4L))
    }

    @Test
    fun `with no usable default the picker lists the categories in order, unchecked`() {
        val ask = batchCategories(categories, defaultCategoryId = -1) as BatchCategories.Ask

        ask.initialSelection.map { it.value.id to (it is CheckboxState.State.Checked) } shouldBe
            listOf(3L to false, 4L to false)
    }
}
