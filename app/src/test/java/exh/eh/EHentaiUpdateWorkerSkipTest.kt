package exh.eh

import exh.metadata.metadata.EHentaiSearchMetadata
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.hours

/** Which galleries a check run leaves out, including the debug menu's one-day floor. */
class EHentaiUpdateWorkerSkipTest {

    private val now = 1_000.hours.inWholeMilliseconds

    private fun gallery(checkedHoursAgo: Int, aged: Boolean = false) = EHentaiSearchMetadata().apply {
        lastUpdateCheck = now - checkedHoursAgo.hours.inWholeMilliseconds
        this.aged = aged
    }

    @Test
    fun `a gallery checked within a day waits while the floor is on`() {
        EHentaiUpdateWorker.isSkipped(gallery(checkedHoursAgo = 2), now, restrictFrequency = true) shouldBe true
    }

    @Test
    fun `a gallery checked within a day is checked again once the floor is off`() {
        EHentaiUpdateWorker.isSkipped(gallery(checkedHoursAgo = 2), now, restrictFrequency = false) shouldBe false
    }

    @Test
    fun `a gallery last checked over a day ago is checked`() {
        EHentaiUpdateWorker.isSkipped(gallery(checkedHoursAgo = 30), now, restrictFrequency = true) shouldBe false
    }

    @Test
    fun `a dead gallery is never checked`() {
        val dead = gallery(checkedHoursAgo = 30, aged = true)

        EHentaiUpdateWorker.isSkipped(dead, now, restrictFrequency = false) shouldBe true
    }
}
