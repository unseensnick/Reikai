package reikai.presentation.library

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType

class LibraryEngineTest {

    private fun provider(type: ContentType): LibraryProvider {
        val provider = mockk<LibraryProvider>(relaxed = true)
        every { provider.contentType } returns type
        return provider
    }

    private val manga = provider(ContentType.MANGA)
    private val novel = provider(ContentType.NOVELS)
    private val engine = LibraryEngine(listOf(manga, novel))

    private val m1 = EntryId.Manga(1)
    private val m2 = EntryId.Manga(2)
    private val m3 = EntryId.Manga(3)

    // A row id is only unique within one content type, so these two are different entries.
    private val n1 = EntryId.Novel(1)

    private val category = 7L

    @Test
    fun `a single content type drives its own provider`() {
        engine.behaviorFor(ContentType.MANGA) shouldBe manga
        engine.behaviorFor(ContentType.NOVELS) shouldBe novel
    }

    @Test
    fun `ALL fans out to every provider`() {
        engine.providersFor(ContentType.ALL) shouldContainExactly listOf(manga, novel)
    }

    @Test
    fun `a mixed view fails loudly instead of rendering one content type`() {
        shouldThrow<IllegalStateException> { engine.behaviorFor(ContentType.ALL) }
    }

    @Test
    fun `toggling adds then removes an entry`() {
        engine.toggleSelection(category, m1)
        engine.selection.value shouldContainExactly setOf(m1)

        engine.toggleSelection(category, m1)
        engine.selection.value.isEmpty() shouldBe true
    }

    @Test
    fun `entries of different content types sharing a row id stay distinct`() {
        engine.toggleSelection(category, m1)
        engine.toggleSelection(category, n1)
        engine.selection.value shouldContainExactlyInAnyOrder listOf(m1, n1)
    }

    @Test
    fun `a range select spans both content types`() {
        val ordered = listOf(m1, n1, m2)
        engine.toggleSelection(category, m1)
        engine.toggleRangeSelection(category, m2, ordered)
        engine.selection.value shouldContainExactlyInAnyOrder ordered
    }

    @Test
    fun `a range select in a different category selects only the tapped entry`() {
        engine.toggleSelection(category, m1)
        engine.toggleRangeSelection(category + 1, m3, listOf(m1, m2, m3))
        engine.selection.value shouldContainExactlyInAnyOrder listOf(m1, m3)
    }

    @Test
    fun `selecting all in a category deselects them when all are already selected`() {
        val ordered = listOf(m1, m2)
        engine.selectAllInCategory(ordered)
        engine.selection.value shouldContainExactlyInAnyOrder ordered

        engine.selectAllInCategory(ordered)
        engine.selection.value.isEmpty() shouldBe true
    }

    @Test
    fun `inverting swaps selected for unselected within the category`() {
        engine.toggleSelection(category, m1)
        engine.invertSelection(listOf(m1, m2, m3))
        engine.selection.value shouldContainExactlyInAnyOrder listOf(m2, m3)
    }

    @Test
    fun `a bulk action reaches every provider and clears the selection`() {
        engine.toggleSelection(category, m1)
        engine.markReadSelection(ContentType.ALL, read = true)

        io.mockk.verify { manga.markReadSelection(setOf(m1), true) }
        io.mockk.verify { novel.markReadSelection(setOf(m1), true) }
        engine.selection.value.isEmpty() shouldBe true
    }

    @Test
    fun `opening a dialog keeps the selection until the dialog resolves`() {
        engine.toggleSelection(category, m1)
        engine.openDeleteDialog(ContentType.MANGA)
        engine.selection.value shouldContainExactly setOf(m1)
    }
}
