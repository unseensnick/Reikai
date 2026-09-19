package reikai.novel.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelDownloadPacingTest {

    @Test
    fun `a source with its own delay uses it`() {
        NovelDownloadPacing.floorFor("slow", globalMs = 500, perSourceMs = mapOf("slow" to 3_000L)) shouldBe 3_000L
    }

    @Test
    fun `a source without one uses the global delay`() {
        NovelDownloadPacing.floorFor("other", globalMs = 500, perSourceMs = mapOf("slow" to 3_000L)) shouldBe 500L
    }

    @Test
    fun `a success halves the wait but never below the user's delay`() {
        NovelDownloadPacing.next(currentMs = 4_000, succeeded = true, floorMs = 3_000) shouldBe 3_000L
    }

    @Test
    fun `a failure doubles the wait up to the cap`() {
        NovelDownloadPacing.next(currentMs = 20_000, succeeded = false, floorMs = 500) shouldBe
            NovelDownloadPacing.MAX_DELAY_MS
    }

    /** A delay set above the cap must not be cut down to it by a failure. */
    @Test
    fun `a failure never waits less than a delay set above the cap`() {
        NovelDownloadPacing.next(currentMs = 40_000, succeeded = false, floorMs = 40_000) shouldBe 40_000L
    }

    @Test
    fun `stored delays read back as they were written`() {
        val delays = mapOf("novelarrow" to 2_000L, "site=with=equals" to 5_000L)

        NovelDownloadPacing.parse(NovelDownloadPacing.format(delays)) shouldBe delays
    }

    @Test
    fun `a malformed stored delay is skipped`() {
        NovelDownloadPacing.parse(setOf("novelarrow=abc", "=500", "ok=1000")) shouldBe mapOf("ok" to 1_000L)
    }
}
