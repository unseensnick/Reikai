package reikai.presentation.details

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

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
    fun `a tag differing from another only in surrounding spaces is not added again`() {
        appendTags(listOf("Fantasy"), listOf(" fantasy ")) shouldBe listOf("Fantasy")
    }

    @Test
    fun `a tag named twice in one addition is added once`() {
        appendTags(emptyList(), listOf("Action", "action")) shouldBe listOf("Action")
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
    fun `a drop on a chip takes its place`() {
        tagDropIndex(ROW_OF_TWO, Offset(150f, 10f)) shouldBe 1
    }

    @Test
    fun `a drop past the last chip goes to the end`() {
        tagDropIndex(ROW_OF_TWO, Offset(500f, 10f)) shouldBe 1
    }

    @Test
    fun `a drop below every chip goes to the end`() {
        tagDropIndex(ROW_OF_TWO, Offset(10f, 300f)) shouldBe 1
    }

    @Test
    fun `a drop in a gap between chips moves nothing`() {
        tagDropIndex(ROW_OF_TWO, Offset(105f, 10f)) shouldBe null
    }

    @Test
    fun `a chip not laid out yet takes no drop`() {
        tagDropIndex(listOf(Rect.Zero, Rect(110f, 0f, 200f, 20f)), Offset(0f, 0f)) shouldBe null
    }

    private companion object {
        val ROW_OF_TWO = listOf(Rect(0f, 0f, 100f, 20f), Rect(110f, 0f, 200f, 20f))
    }
}
