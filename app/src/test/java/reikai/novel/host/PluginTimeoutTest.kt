package reikai.novel.host

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * A plugin call that runs past its deadline is the plugin failing, not the caller being cancelled.
 * Surfaced as a cancellation it was rethrown by every caller's cancellation clause, and the
 * migration driver took it for an abandoned row and searched that row again forever.
 */
class PluginTimeoutTest {

    @Test
    fun `a call past its deadline fails as a plugin error`() = runTest {
        shouldThrow<LnPluginException> {
            withPluginTimeout(1_000L, "searchNovels") {
                delay(10_000L)
                "late"
            }
        }
    }

    // A plugin retrying a site that keeps answering 521 runs out its time; the error still names the 521.
    @Test
    fun `a call past its deadline carries the cause it was given`() = runTest {
        val failedFetch = IOException("HTTP error 521")

        val timedOut = shouldThrow<LnPluginException> {
            withPluginTimeout(1_000L, "parseChapter", { failedFetch }) {
                delay(10_000L)
                "late"
            }
        }

        timedOut.cause shouldBe failedFetch
    }
}
