package reikai.presentation.details

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** The tag rules the edit-info dialog applies, for manga and novels alike. */
class EntryTagsTest {

    @Test
    fun `existing tags stay and new ones are appended`() {
        appendTags(listOf("ROMANCE", "FANTASY"), listOf("Mystery", "Supernatural")) shouldBe
            listOf("ROMANCE", "FANTASY", "Mystery", "Supernatural")
    }

    @Test
    fun `a tag differing from another only in case is not added again`() {
        appendTags(listOf("FANTASY"), listOf("Fantasy", "Mystery")) shouldBe listOf("FANTASY", "Mystery")
    }

    @Test
    fun `a padded new tag is stored trimmed`() {
        appendTags(emptyList(), listOf(" Action ")) shouldBe listOf("Action")
    }

    @Test
    fun `a tag differing from another only in surrounding spaces is not added again`() {
        appendTags(listOf("Fantasy"), listOf(" fantasy ")) shouldBe listOf("Fantasy")
    }

    @Test
    fun `a tag named twice in one addition is added once`() {
        appendTags(emptyList(), listOf("Action", "action")) shouldBe listOf("Action")
    }

    @Test
    fun `a tag differing from another only in its separators is not added again`() {
        appendTags(listOf("Shoujo Ai"), listOf("Shoujo-ai", "Slice_of_Life")) shouldBe
            listOf("Shoujo Ai", "Slice_of_Life")
    }

    @Test
    fun `a blank tag is dropped`() {
        appendTags(listOf("Drama"), listOf(" ")) shouldBe listOf("Drama")
    }

    @Test
    fun `typed tags split on commas and lose their spaces`() {
        typedTags(" Action, Slice of Life ,,") shouldBe listOf("Action", "Slice of Life")
    }

    @Test
    fun `an entry that is not merged has no source tags to collate`() = runTest {
        groupSourceTags(longArrayOf(1)) { listOf("Action") } shouldBe null
    }

    @Test
    fun `a merged series collates every source's tags`() = runTest {
        val tags = mapOf(1L to listOf("Action"), 2L to null, 3L to listOf("Drama"))

        groupSourceTags(longArrayOf(1, 2, 3)) { tags[it] } shouldBe listOf("Action", "Drama")
    }

    @Test
    fun `a tag two sources spell in different case is collated once`() = runTest {
        val tags = mapOf(1L to listOf("Action"), 2L to listOf("action", "Drama"))

        groupSourceTags(longArrayOf(1, 2)) { tags[it] } shouldBe listOf("Action", "Drama")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("drops")
    fun `a drop lands on the chip under it, at the end past them, and nowhere in a gap`(
        @Suppress("UNUSED_PARAMETER") case: String,
        chips: List<Rect>,
        x: Float,
        y: Float,
        index: Int?,
    ) {
        tagDropIndex(chips, Offset(x, y)) shouldBe index
    }

    companion object {
        private val ROW_OF_TWO = listOf(Rect(0f, 0f, 100f, 20f), Rect(110f, 0f, 200f, 20f))

        @JvmStatic
        fun drops() = listOf(
            Arguments.of("on a chip", ROW_OF_TWO, 150f, 10f, 1),
            Arguments.of("past the last chip", ROW_OF_TWO, 500f, 10f, 1),
            Arguments.of("below every chip", ROW_OF_TWO, 10f, 300f, 1),
            Arguments.of("in a gap", ROW_OF_TWO, 105f, 10f, null),
            Arguments.of("on a chip not laid out yet", listOf(Rect.Zero, ROW_OF_TWO[1]), 0f, 0f, null),
        )
    }
}
