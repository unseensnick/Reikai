package reikai.domain.category

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class CategoryDiffTest {

    @Test
    fun `common is the intersection, mix is the rest of the union`() {
        categoryDiff(listOf(setOf(1L, 2L, 3L), setOf(2L, 3L, 4L))) shouldBe
            CategoryDiff(common = setOf(2L, 3L), mix = setOf(1L, 4L))
    }

    @Test
    fun `categories on every entry are all common, none mixed`() {
        categoryDiff(listOf(setOf(1L, 2L), setOf(1L, 2L))) shouldBe
            CategoryDiff(common = setOf(1L, 2L), mix = emptySet())
    }

    @Test
    fun `categories shared by none are all mixed`() {
        categoryDiff(listOf(setOf(1L), setOf(2L))) shouldBe
            CategoryDiff(common = emptySet(), mix = setOf(1L, 2L))
    }

    @Test
    fun `a single entry makes all its categories common`() {
        categoryDiff(listOf(setOf(1L, 2L, 3L))) shouldBe
            CategoryDiff(common = setOf(1L, 2L, 3L), mix = emptySet())
    }

    @Test
    fun `an empty selection yields empty common and mix`() {
        categoryDiff(emptyList()) shouldBe CategoryDiff(common = emptySet(), mix = emptySet())
    }
}
