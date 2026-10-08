package reikai.presentation.library

import eu.kanade.presentation.library.components.LibraryToolbarTitle
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LibraryToolbarTitleRuleTest {

    private fun title(
        showCategoryInTitle: Boolean = false,
        showCategoryTabs: Boolean = false,
        showAllCategories: Boolean = false,
        showItemCounts: Boolean = true,
    ) = libraryToolbarTitle(
        defaultTitle = "Library",
        sectionLabel = "Default",
        showCategoryInTitle = showCategoryInTitle,
        showCategoryTabs = showCategoryTabs,
        showAllCategories = showAllCategories,
        showItemCounts = showItemCounts,
        sectionCount = { 44 },
        wholeCount = { 124 },
    )

    @Test
    fun `the single-list view reads Library with the whole count`() {
        title(showAllCategories = true) shouldBe LibraryToolbarTitle("Library", 124)
    }

    @Test
    fun `the single-list view names the section when always show current category is on`() {
        title(showAllCategories = true, showCategoryInTitle = true) shouldBe LibraryToolbarTitle("Default", 44)
    }

    @Test
    fun `the paged view without tabs names the category on screen`() {
        title() shouldBe LibraryToolbarTitle("Default", 44)
    }

    @Test
    fun `the tabbed view reads Library with the whole count`() {
        title(showCategoryTabs = true) shouldBe LibraryToolbarTitle("Library", 124)
    }

    @Test
    fun `counts off leaves the title without a number`() {
        title(showAllCategories = true, showItemCounts = false) shouldBe LibraryToolbarTitle("Library", null)
    }
}
