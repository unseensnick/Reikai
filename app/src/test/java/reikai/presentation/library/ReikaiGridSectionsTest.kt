package reikai.presentation.library

import eu.kanade.tachiyomi.ui.library.LibraryItem
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * The single list's layout, which the grid builds from and the hopper, picker and fast-scroll thumb index
 * into: a search item, then per bucket a header and, unless collapsed, its cells.
 */
class ReikaiGridSectionsTest {

    private val open: LibraryBucket = LibraryBucket.Dynamic("open", "Open")
    private val shut: LibraryBucket = LibraryBucket.Dynamic("shut", "Shut")
    private val last: LibraryBucket = LibraryBucket.Dynamic("last", "Last")
    private val items = mapOf(open to cells(5), shut to cells(3), last to cells(2))

    private val sections = reikaiGridSections(
        buckets = listOf(open, shut, last),
        hasSearchItem = true,
        isCollapsed = { it == shut },
        itemsOf = { items.getValue(it) },
    )

    @Test
    fun `each header sits after the search item and the open buckets' cells above it`() {
        reikaiCategoryHeaderIndices(sections) shouldBe listOf(1, 7, 8)
    }

    @Test
    fun `rows start at the search item, each header and every columns-th cell`() {
        reikaiRowStartIndices(sections, columns = 2) shouldBe listOf(0, 1, 2, 4, 6, 7, 8, 9)
    }

    private fun cells(count: Int) = List(count) { mockk<LibraryItem>() }
}
