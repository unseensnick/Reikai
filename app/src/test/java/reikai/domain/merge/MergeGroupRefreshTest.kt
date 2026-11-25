package reikai.domain.merge

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/** Both details screens refresh a merged group through this one rule. */
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

    @Test
    fun `a sibling's failure is reported when the anchor succeeds`() = runTest {
        val error = refreshMergeGroup(
            anchor = { Result.success(Unit) },
            siblings = listOf("broken"),
            sourceOf = { sources[it] },
            refresh = { _, _ -> Result.failure<Unit>(siblingFailure) },
        )

        error shouldBe siblingFailure
    }

    @Test
    fun `the first failure in member order wins over one that failed sooner`() = runTest {
        val later = IllegalStateException("later sibling failed")
        val error = refreshMergeGroup(
            anchor = { Result.success(Unit) },
            siblings = listOf("broken", "installed"),
            sourceOf = { sources[it] },
            refresh = { sibling, _ ->
                if (sibling == "broken") {
                    delay(SLOW_MS)
                    Result.failure<Unit>(siblingFailure)
                } else {
                    Result.failure<Unit>(later)
                }
            },
        )

        error shouldBe siblingFailure
    }

    @Test
    fun `a slow source does not hold up the others`() = runTest {
        val finishedAt = mutableMapOf<String, Long>()
        refreshMergeGroup(
            anchor = {
                delay(SLOW_MS)
                Result.success(Unit)
            },
            siblings = listOf("broken", "installed"),
            sourceOf = { sources[it] },
            refresh = { sibling, _ ->
                if (sibling == "broken") delay(SLOW_MS)
                finishedAt[sibling] = currentTime
                Result.success(Unit)
            },
        )

        finishedAt["installed"] shouldBe 0L
    }

    private companion object {
        const val SLOW_MS = 30_000L
    }
}
