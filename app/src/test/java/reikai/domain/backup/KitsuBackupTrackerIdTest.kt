package reikai.domain.backup

import eu.kanade.tachiyomi.data.track.TrackerManager
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.data.track.installTrackerTestGraph
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektScope

/** The scale restates Kitsu's id, since :domain cannot see TrackerManager, so only this binds the two. */
class KitsuBackupTrackerIdTest {

    private lateinit var replaced: InjektScope

    @BeforeEach
    fun setUp() {
        replaced = installTrackerTestGraph()
    }

    @AfterEach
    fun tearDown() {
        Injekt = replaced
    }

    @Test
    fun `the backup scale rescales the tracker TrackerManager calls Kitsu`() {
        val id = TrackerManager().kitsu.id

        KitsuBackupScoreScale.of(nativeScaleMarked = false, listOf(id to 4.0)).restore(id, 4.0) shouldBe 8.0
    }
}
