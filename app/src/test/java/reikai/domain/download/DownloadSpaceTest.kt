package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The free-space floor both downloaders check before fetching a chapter. */
class DownloadSpaceTest {

    @Test
    fun `a nearly full volume has no room`() {
        hasRoomToDownload(199L * 1024 * 1024) shouldBe false
    }

    @Test
    fun `a volume whose space cannot be read is let through`() {
        hasRoomToDownload(-1L) shouldBe true
    }

    @Test
    fun `a copy that would take the volume below the floor has no room`() {
        hasRoomToCopy(availableBytes = 300L * 1024 * 1024, bytes = 101L * 1024 * 1024) shouldBe false
    }

    @Test
    fun `a copy whose volume cannot be read is let through`() {
        hasRoomToCopy(availableBytes = -1L, bytes = 1L) shouldBe true
    }
}
