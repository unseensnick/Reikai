package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadPacingTest {

    @Test
    fun `a source with its own delay uses it`() {
        DownloadPacing.floorFor(
            "slow",
            globalMs = 500,
            perSourceMs = mapOf("slow" to 3_000L),
            minimumMs = 0,
        ) shouldBe
            3_000L
    }

    @Test
    fun `a source without one uses the global delay`() {
        DownloadPacing.floorFor(
            "other",
            globalMs = 500,
            perSourceMs = mapOf("slow" to 3_000L),
            minimumMs = 0,
        ) shouldBe
            500L
    }

    @Test
    fun `a source's own delay below the least it declares is lifted to it`() {
        DownloadPacing.floorFor(
            "fast",
            globalMs = 500,
            perSourceMs = mapOf("fast" to 500L),
            minimumMs = 1_000,
        ) shouldBe
            1_000L
    }

    @Test
    fun `the global delay below a source's declared least is lifted to it`() {
        DownloadPacing.floorFor("other", globalMs = 500, perSourceMs = emptyMap(), minimumMs = 1_000) shouldBe
            1_000L
    }

    @Test
    fun `a delay above a source's declared least is kept`() {
        DownloadPacing.floorFor(
            "slow",
            globalMs = 500,
            perSourceMs = mapOf("slow" to 3_000L),
            minimumMs = 1_000,
        ) shouldBe
            3_000L
    }

    @Test
    fun `a source is offered no delay below the least it declares`() {
        DownloadPacing.delayOptions(minimumMs = 1_000) shouldBe
            listOf(1_000L, 1_500L, 2_000L, 3_000L, 5_000L, 8_000L, 10_000L, 15_000L, 30_000L)
    }

    @Test
    fun `a source that declares nothing is offered every delay`() {
        DownloadPacing.delayOptions(minimumMs = 0) shouldBe DownloadPacing.DELAY_OPTIONS_MS
    }

    @Test
    fun `a success halves the wait`() {
        DownloadPacing.next(currentMs = 8_000, succeeded = true, floorMs = 500) shouldBe 4_000L
    }

    @Test
    fun `a failure doubles the wait`() {
        DownloadPacing.next(currentMs = 2_000, succeeded = false, floorMs = 500) shouldBe 4_000L
    }

    /** The user raised the delay while downloads ran, so the kept wait sits below the new one. */
    @Test
    fun `a failure doubles from the user's delay when the kept wait is below it`() {
        DownloadPacing.next(currentMs = 500, succeeded = false, floorMs = 5_000) shouldBe 10_000L
    }

    @Test
    fun `a success halves the wait but never below the user's delay`() {
        DownloadPacing.next(currentMs = 4_000, succeeded = true, floorMs = 3_000) shouldBe 3_000L
    }

    @Test
    fun `a failure doubles the wait up to the cap`() {
        DownloadPacing.next(currentMs = 20_000, succeeded = false, floorMs = 500) shouldBe
            DownloadPacing.MAX_DELAY_MS
    }

    /** A delay set above the cap must not be cut down to it by a failure. */
    @Test
    fun `a failure never waits less than a delay set above the cap`() {
        DownloadPacing.next(currentMs = 40_000, succeeded = false, floorMs = 40_000) shouldBe 40_000L
    }

    @Test
    fun `stored delays read back as they were written`() {
        val delays = mapOf("novelarrow" to 2_000L, "site=with=equals" to 5_000L)

        DownloadPacing.parse(DownloadPacing.format(delays)) shouldBe delays
    }

    @Test
    fun `a malformed stored delay is skipped`() {
        DownloadPacing.parse(setOf("novelarrow=abc", "=500", "ok=1000")) shouldBe mapOf("ok" to 1_000L)
    }

    /** Off is what manga starts at; a wait there would slow every existing manga download. */
    @Test
    fun `Off never waits, not even after a failure`() {
        DownloadPacing.next(currentMs = 0, succeeded = false, floorMs = 0) shouldBe 0L
    }

    /** Each delay the old list could save is a stop, so an upgrade shows and keeps the stored value. */
    @Test
    fun `every delay the old list offered is a slider stop`() {
        DownloadPacing.DELAY_OPTIONS_MS.containsAll(listOf(500L, 1_000L, 2_000L, 3_000L, 5_000L, 10_000L)) shouldBe
            true
    }

    @Test
    fun `the jitter never waits less than the pace`() {
        DownloadPacing.jittered(paceMs = 2_000, random = 0.0) shouldBe 2_000L
    }

    @Test
    fun `the jitter adds at most a quarter`() {
        DownloadPacing.jittered(paceMs = 2_000, random = 1.0) shouldBe 2_500L
    }

    @Test
    fun `a delay limits how many chapters a minute start`() {
        DownloadPacing.chaptersPerMinute(3_000) shouldBe 20
    }

    @Test
    fun `Off sets no chapters-a-minute limit`() {
        DownloadPacing.chaptersPerMinute(0) shouldBe null
    }
}
