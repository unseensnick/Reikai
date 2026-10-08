package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

/** A failed manga page and a failed novel chapter wait the same before each retry. */
class DownloadRetryTest {

    @Test
    fun `three retries wait two, four and eight seconds`() {
        (0 until DownloadRetry.MAX_RETRIES).map(DownloadRetry::delayBefore) shouldBe
            listOf(2.seconds, 4.seconds, 8.seconds)
    }
}
