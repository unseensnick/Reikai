package reikai.domain.download

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** The exclusion every removal path of both content types honours, so the rule is pinned here once. */
class RemovalExclusionTest {

    /** Whether a read chapter stays when [excluded] is kept from removal and its entry sits in [categoryIds]. */
    private suspend fun keepsRead(excluded: Set<String>, categoryIds: suspend () -> List<Long>) = removableDownloads(
        listOf("read"),
        excluded,
        allowBookmarked = true,
        isRead = { true },
        isBookmarked = { false },
        categoryIds = categoryIds,
    ).isEmpty()

    @Test
    fun `an uncategorized novel is kept when Default is excluded`() = runTest {
        keepsRead(setOf("0")) { emptyList() } shouldBe true
    }

    @Test
    fun `a novel in an excluded category is kept`() = runTest {
        keepsRead(setOf("11")) { listOf(4L, 11L) } shouldBe true
    }

    @Test
    fun `a categorized novel is not kept by excluding Default`() = runTest {
        keepsRead(setOf("0")) { listOf(4L) } shouldBe false
    }

    @Test
    fun `nothing excluded never looks up the categories`() = runTest {
        keepsRead(emptySet()) { error("looked up categories with nothing excluded") } shouldBe false
    }
}
