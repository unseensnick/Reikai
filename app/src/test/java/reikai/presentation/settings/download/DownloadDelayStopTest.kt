package reikai.presentation.settings.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Where a delay sits on the slider, which must not rewrite it until the user drags. */
class DownloadDelayStopTest {

    private val options = listOf(0L, 500L, 1_000L, 2_000L, 5_000L)
    private val defaultMs = 500L

    @Test
    fun `a stored delay on a stop shows at that stop`() {
        stopIndex(options, 2_000L, defaultMs) shouldBe 3
    }

    @Test
    fun `a stored delay between stops shows at the next stop up`() {
        stopIndex(options, 1_500L, defaultMs) shouldBe 3
    }

    @Test
    fun `a delay above every stop shows at the last`() {
        stopIndex(options, 60_000L, defaultMs) shouldBe 4
    }

    /** A delay of its own equal to the default keeps its own stop rather than moving. */
    @Test
    fun `a source's own delay equal to the default stays on that stop`() {
        stopIndex(options, 500L, defaultMs) shouldBe 1
    }

    /** Default is no stop of its own, so the same delay is never listed twice. */
    @Test
    fun `a source following the default sits on the stop that default works out to`() {
        stopIndex(options, null, 1_000L) shouldBe 2
    }
}
