package reikai.domain.backup

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.data.track.trackerTestManager

/** The scale restates Kitsu's id, since :domain cannot see TrackerManager, so only this binds the two. */
class KitsuBackupTrackerIdTest {

    @Test
    fun `the backup scale rescales the tracker TrackerManager calls Kitsu`() {
        val id = trackerTestManager().kitsu.id

        KitsuBackupScoreScale.of(nativeScaleMarked = false, listOf(id to 4.0)).restore(id, 4.0) shouldBe 8.0
    }
}
