package reikai.presentation.reader.text

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import reikai.presentation.reader.text.NovelWarmPolicy.RETRY_COOLDOWN_MS

/**
 * Pins the rule that a failed neighbour chapter is left alone for a while and then reachable again,
 * which is what stops a boundary from re-requesting it on every crossing without stranding the
 * session on a chapter that has recovered.
 */
class NovelWarmPolicyTest {

    @Test
    @DisplayName("a chapter that has not failed may be warmed")
    fun neverFailedIsAllowed() {
        NovelWarmPolicy.mayAutoWarm(failure = null, nowElapsedMs = 0L) shouldBe true
    }

    @Test
    @DisplayName("a chapter that just failed is not warmed again immediately")
    fun freshFailureIsDenied() {
        NovelWarmPolicy.mayAutoWarm(failedAt(1_000L), nowElapsedMs = 1_000L) shouldBe false
    }

    @Test
    @DisplayName("a chapter still inside the cooldown is not warmed again")
    fun withinCooldownIsDenied() {
        NovelWarmPolicy.mayAutoWarm(failedAt(1_000L), nowElapsedMs = 1_000L + RETRY_COOLDOWN_MS - 1) shouldBe false
    }

    @Test
    @DisplayName("the cooldown clears itself on its last millisecond")
    fun exactlyAtCooldownIsAllowed() {
        NovelWarmPolicy.mayAutoWarm(failedAt(1_000L), nowElapsedMs = 1_000L + RETRY_COOLDOWN_MS) shouldBe true
    }

    @Test
    @DisplayName("a chapter left alone past the cooldown may be warmed again")
    fun pastCooldownIsAllowed() {
        NovelWarmPolicy.mayAutoWarm(failedAt(1_000L), nowElapsedMs = 1_000L + RETRY_COOLDOWN_MS * 4) shouldBe true
    }

    private fun failedAt(elapsedMs: Long) = NovelWarmPolicy.Failure(elapsedMs, message = "offline")
}
