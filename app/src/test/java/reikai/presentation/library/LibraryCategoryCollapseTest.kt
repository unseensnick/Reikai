package reikai.presentation.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.ReikaiLibraryPreferences
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.category.model.Category

/**
 * Collapse is one library-wide value written through these three helpers, so both content types share it:
 * a category is one row in one list, whichever chip is filtering the view.
 */
class LibraryCategoryCollapseTest {

    private val preferences = ReikaiLibraryPreferences(InMemoryPreferenceStore())

    private fun category(id: Long, name: String) = Category(id = id, name = name, order = 0, flags = 0)

    // A dynamic group is a synthetic category with a negative id, keyed by its encoded name.
    private fun dynamic(name: String) = category(-1, name)

    @Test
    fun `collapsing a real category stores its id`() {
        preferences.toggleCategoryCollapsed("7")

        preferences.collapsedCategories.get() shouldBe setOf("7")
    }

    @Test
    fun `collapsing the same category twice expands it again`() {
        preferences.toggleCategoryCollapsed("7")
        preferences.toggleCategoryCollapsed("7")

        preferences.collapsedCategories.get() shouldBe emptySet()
    }

    @Test
    fun `a dynamic group collapses into its own set`() {
        preferences.toggleDynamicCategoryCollapsed("Action")

        preferences.collapsedDynamicCategories.get() shouldBe setOf("Action")
    }

    @Test
    fun `a dynamic group never lands in the real-category set`() {
        preferences.toggleDynamicCategoryCollapsed("Action")

        preferences.collapsedCategories.get() shouldBe emptySet()
    }

    @Test
    fun `collapse all splits real categories from dynamic groups`() {
        preferences.toggleAllCategoriesCollapsed(listOf(category(7, "Reading"), dynamic("Action")))

        preferences.collapsedCategories.get() to preferences.collapsedDynamicCategories.get() shouldBe
            (setOf("7") to setOf("Action"))
    }

    @Test
    fun `collapse all expands everything when it is already collapsed`() {
        val categories = listOf(category(7, "Reading"), dynamic("Action"))
        preferences.toggleAllCategoriesCollapsed(categories)
        preferences.toggleAllCategoriesCollapsed(categories)

        preferences.collapsedCategories.get() to preferences.collapsedDynamicCategories.get() shouldBe
            (emptySet<String>() to emptySet<String>())
    }
}
