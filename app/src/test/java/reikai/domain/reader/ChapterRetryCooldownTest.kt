package reikai.domain.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.domain.reader.ChapterRetryCooldown.RETRY_COOLDOWN_MS

/**
 * Pins the rule that a failed neighbour chapter is left alone for a while and then reachable again,
 * which is what stops a boundary from re-requesting it on every crossing without stranding the
 * session on a chapter that has recovered.
 */
class ChapterRetryCooldownTest {

    @Test
    @DisplayName("a chapter that has not failed may be retried")
    fun neverFailedIsAllowed() {
        ChapterRetryCooldown.mayRetryUnprompted(failure = null, nowElapsedMs = 0L) shouldBe true
    }

    @Test
    @DisplayName("a chapter that just failed is not retried again immediately")
    fun freshFailureIsDenied() {
        ChapterRetryCooldown.mayRetryUnprompted(failedAt(1_000L), nowElapsedMs = 1_000L) shouldBe false
    }

    @Test
    @DisplayName("a chapter still inside the cooldown is not retried again")
    fun withinCooldownIsDenied() {
        ChapterRetryCooldown.mayRetryUnprompted(
            failedAt(1_000L),
            nowElapsedMs = 1_000L + RETRY_COOLDOWN_MS - 1,
        ) shouldBe
            false
    }

    @Test
    @DisplayName("the cooldown clears itself on its last millisecond")
    fun exactlyAtCooldownIsAllowed() {
        ChapterRetryCooldown.mayRetryUnprompted(failedAt(1_000L), nowElapsedMs = 1_000L + RETRY_COOLDOWN_MS) shouldBe
            true
    }

    @Test
    @DisplayName("a chapter left alone past the cooldown may be retried again")
    fun pastCooldownIsAllowed() {
        ChapterRetryCooldown.mayRetryUnprompted(
            failedAt(1_000L),
            nowElapsedMs = 1_000L + RETRY_COOLDOWN_MS * 4,
        ) shouldBe
            true
    }

    /** A chapter that keeps failing is left alone for a whole cooldown after each attempt, not reached
     *  for again as soon as the first failure's cooldown runs out. */
    @Test
    @DisplayName("a second failure restarts the cooldown")
    fun repeatedFailureRestartsTheCooldown() {
        val failures = ChapterRetryCooldown.Failures()
        failures.record(CHAPTER, nowElapsedMs = 0L, message = "offline")
        failures.record(CHAPTER, nowElapsedMs = 60_000L, message = "offline")
        failures.mayRetryUnprompted(CHAPTER, nowElapsedMs = 61_000L) shouldBe false
    }

    private companion object {
        const val CHAPTER = 7L
    }

    private fun failedAt(elapsedMs: Long) = ChapterRetryCooldown.Failure(elapsedMs, message = "offline")
}
