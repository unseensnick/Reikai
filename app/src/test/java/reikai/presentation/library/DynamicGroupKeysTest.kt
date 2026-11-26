package reikai.presentation.library

import io.kotest.matchers.collections.shouldBeEmpty
import org.junit.jupiter.api.Test
import reikai.data.track.trackerTestManager

class DynamicGroupKeysTest {

    @Test
    fun `every tracker status has a neutral group key`() {
        val statuses = trackerTestManager().trackers.flatMap { tracker ->
            tracker.getStatusList().mapNotNull { tracker.getStatus(it) }
        }

        statuses.filterNot { it in DynamicGroupKeys.TRACK_STATUSES }.shouldBeEmpty()
    }
}
