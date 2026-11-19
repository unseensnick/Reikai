package reikai.presentation.notes

import androidx.compose.runtime.Composable
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.presentation.details.detailsScreen

/** A notes page whose subject is gone closes the same way for both types, and for a volume. */
class CloseMissingNotesTest {

    @ParameterizedTest
    @MethodSource("entries")
    fun `a missing series' notes close with its details page`(entry: EntryId) {
        val other = EntryId.of(entry.contentType, entry.rawId + 1).detailsScreen()
        val stack = mutableStateStackOf(Root, other, entry.detailsScreen(), Notes)

        stack.closeMissingNotes(entry)

        stack.items shouldBe listOf(Root, other)
    }

    // The volume may be gone from a series still there, whose page reads it fine.
    @Test
    fun `a missing volume's notes leave the series page open`() {
        val details = EntryId.Manga(1L).detailsScreen()
        val stack = mutableStateStackOf(Root, details, Notes)

        stack.closeMissingNotes(series = null)

        stack.items shouldBe listOf(Root, details)
    }

    private object Root : Screen {
        @Composable
        override fun Content() = Unit
    }

    private object Notes : Screen {
        @Composable
        override fun Content() = Unit
    }

    companion object {
        @JvmStatic
        fun entries() = listOf(EntryId.Manga(1L), EntryId.Novel(1L))
    }
}
