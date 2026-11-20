package reikai.presentation.notes

import androidx.navigation3.runtime.NavKey
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.presentation.details.detailsRoute
import reikai.presentation.testNavigator

/** A notes page whose subject is gone closes the same way for both types, and for a volume. */
class CloseMissingNotesTest {

    @ParameterizedTest
    @MethodSource("entries")
    fun `a missing series' notes close with its details page`(entry: EntryId) {
        val other = EntryId.of(entry.contentType, entry.rawId + 1).detailsRoute()
        val stack = testNavigator(Root, other, entry.detailsRoute(), Notes)

        stack.closeMissingNotes(entry)

        stack.routes shouldBe listOf(Root, other)
    }

    // The volume may be gone from a series still there, whose page reads it fine.
    @Test
    fun `a missing volume's notes leave the series page open`() {
        val details = EntryId.Manga(1L).detailsRoute()
        val stack = testNavigator(Root, details, Notes)

        stack.closeMissingNotes(series = null)

        stack.routes shouldBe listOf(Root, details)
    }

    @Serializable
    private data object Root : NavKey

    @Serializable
    private data object Notes : NavKey

    companion object {
        @JvmStatic
        fun entries() = listOf(EntryId.Manga(1L), EntryId.Novel(1L))
    }
}
