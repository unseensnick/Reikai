package reikai.domain.merge

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * Both details screens refresh a merged group through this one rule. A sibling from an uninstalled source
 * used to fail every manga refresh while novels skipped it; it is skipped for both now.
 */
class MergeGroupRefreshTest {

    private val anchorFailure = IllegalStateException("anchor source missing")
    private val siblingFailure = IllegalStateException("sibling failed")

    private val sources = mapOf("installed" to "src", "broken" to "src")

    @Test
    fun `a sibling whose source is not installed is skipped without an error`() = runTest {
        val error = refreshMergeGroup(
            anchor = { Result.success(Unit) },
            siblings = listOf("uninstalled"),
            sourceOf = { sources[it] },
            refresh = { _, _ -> Result.success(Unit) },
        )

        error.shouldBeNull()
    }

    @Test
    fun `the anchor's failure is reported`() = runTest {
        val error = refreshMergeGroup(
            anchor = { Result.failure<Unit>(anchorFailure) },
            siblings = listOf("installed"),
            sourceOf = { sources[it] },
            refresh = { _, _ -> Result.success(Unit) },
        )

        error shouldBe anchorFailure
    }

    @Test
    fun `a failing sibling does not stop the ones after it`() = runTest {
        val refreshed = mutableListOf<String>()
        refreshMergeGroup(
            anchor = { Result.success(Unit) },
            siblings = listOf("broken", "installed"),
            sourceOf = { sources[it] },
            refresh = { sibling, _ ->
                refreshed += sibling
                if (sibling == "broken") Result.failure<Unit>(siblingFailure) else Result.success(Unit)
            },
        )

        refreshed shouldContainExactly listOf("broken", "installed")
    }
}
