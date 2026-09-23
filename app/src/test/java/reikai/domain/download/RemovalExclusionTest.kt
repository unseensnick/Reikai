package reikai.domain.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The exclusion every removal path of both content types calls, so the rule is pinned here once. */
class RemovalExclusionTest {

    @Test
    fun `an uncategorized novel is kept when Default is excluded`() = runTest {
        isExcludedFromRemoval(setOf("0")) { emptyList() } shouldBe true
    }

    @Test
    fun `a novel in an excluded category is kept`() = runTest {
        isExcludedFromRemoval(setOf("11")) { listOf(4L, 11L) } shouldBe true
    }

    @Test
    fun `a categorized novel is not kept by excluding Default`() = runTest {
        isExcludedFromRemoval(setOf("0")) { listOf(4L) } shouldBe false
    }

    @Test
    fun `nothing excluded never looks up the categories`() = runTest {
        isExcludedFromRemoval(emptySet()) { error("looked up categories with nothing excluded") } shouldBe false
    }
}
