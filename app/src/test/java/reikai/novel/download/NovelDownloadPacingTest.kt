package reikai.novel.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelDownloadPacingTest {

    @Test
    fun `a source with its own delay uses it`() {
        NovelDownloadPacing.floorFor(
            "slow",
            globalMs = 500,
            perSourceMs = mapOf("slow" to 3_000L),
            minimumMs = 0,
        ) shouldBe
            3_000L
    }

    @Test
    fun `a source without one uses the global delay`() {
        NovelDownloadPacing.floorFor(
            "other",
            globalMs = 500,
            perSourceMs = mapOf("slow" to 3_000L),
            minimumMs = 0,
        ) shouldBe
            500L
    }

    @Test
    fun `a source's own delay below the least it declares is lifted to it`() {
        NovelDownloadPacing.floorFor(
            "fast",
            globalMs = 500,
            perSourceMs = mapOf("fast" to 500L),
            minimumMs = 1_000,
        ) shouldBe
            1_000L
    }

    @Test
    fun `the global delay below a source's declared least is lifted to it`() {
        NovelDownloadPacing.floorFor("other", globalMs = 500, perSourceMs = emptyMap(), minimumMs = 1_000) shouldBe
            1_000L
    }

    @Test
    fun `a delay above a source's declared least is kept`() {
        NovelDownloadPacing.floorFor(
            "slow",
            globalMs = 500,
            perSourceMs = mapOf("slow" to 3_000L),
            minimumMs = 1_000,
        ) shouldBe
            3_000L
    }

    @Test
    fun `a source is offered no delay below the least it declares`() {
        NovelDownloadPacing.delayOptions(minimumMs = 1_000) shouldBe listOf(1_000L, 2_000L, 3_000L, 5_000L, 10_000L)
    }

    @Test
    fun `a source that declares nothing is offered every delay`() {
        NovelDownloadPacing.delayOptions(minimumMs = 0) shouldBe NovelDownloadPacing.DELAY_OPTIONS_MS
    }

    @Test
    fun `a success halves the wait`() {
        NovelDownloadPacing.next(currentMs = 8_000, succeeded = true, floorMs = 500) shouldBe 4_000L
    }

    @Test
    fun `a failure doubles the wait`() {
        NovelDownloadPacing.next(currentMs = 2_000, succeeded = false, floorMs = 500) shouldBe 4_000L
    }

    /** The user raised the delay while downloads ran, so the kept wait sits below the new one. */
    @Test
    fun `a failure doubles from the user's delay when the kept wait is below it`() {
        NovelDownloadPacing.next(currentMs = 500, succeeded = false, floorMs = 5_000) shouldBe 10_000L
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
