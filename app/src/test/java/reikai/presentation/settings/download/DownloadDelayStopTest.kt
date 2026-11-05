package reikai.presentation.settings.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Where a stored delay sits on the slider, which must not rewrite it until the user drags. */
class DownloadDelayStopTest {

    private val options = listOf(null, 1_000L, 2_000L, 5_000L)

    @Test
    fun `a stored delay on a stop shows at that stop`() {
        stopIndex(options, 2_000L) shouldBe 2
    }

    @Test
    fun `a stored delay between stops shows at the next stop up`() {
        stopIndex(options, 1_500L) shouldBe 2
    }

    @Test
    fun `a delay above every stop shows at the last`() {
        stopIndex(options, 60_000L) shouldBe 3
    }

    @Test
    fun `no delay of its own shows at Default`() {
        stopIndex(options, null) shouldBe 0
    }
}
