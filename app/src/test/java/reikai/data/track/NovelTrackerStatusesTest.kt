package reikai.data.track

import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.novellist.NovelList
import eu.kanade.tachiyomi.data.track.novellist.statusOnBind
import eu.kanade.tachiyomi.data.track.novelupdates.BindOnSite
import eu.kanade.tachiyomi.data.track.novelupdates.NovelUpdates
import eu.kanade.tachiyomi.data.track.novelupdates.bindOnSite
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.i18n.MR

class StatusCase(private val label: String, val tracker: () -> BaseTracker) {
    override fun toString() = label
}

/** The three light-novel trackers store and label one status vocabulary, and file an unlisted bind alike. */
class NovelTrackerStatusesTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `every status a tracker offers carries the shared label`(case: StatusCase) {
        val tracker = case.tracker()
        tracker.getStatusList().associateWith(tracker::getStatus) shouldBe
            expectedLabels.filterKeys { it in tracker.getStatusList() }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `with no reread state on the site, a reread stays Reading`(case: StatusCase) {
        val tracker = case.tracker()
        listOf(tracker.getReadingStatus(), tracker.getRereadingStatus(), tracker.getCompletionStatus()) shouldBe
            listOf(1L, 1L, 2L)
    }

    @Test
    fun `a bind of a novel no list holds files it by whether anything was read`() {
        listOf(
            statusOnBind(null, hasReadChapters = true),
            (bindOnSite(null, hasReadChapters = true) as BindOnSite.File).status,
            statusOnBind(null, hasReadChapters = false),
            (bindOnSite(null, hasReadChapters = false) as BindOnSite.File).status,
        ) shouldBe listOf(1L, 1L, 5L, 5L)
    }

    companion object {
        private val expectedLabels = mapOf(
            1L to MR.strings.reading,
            2L to MR.strings.completed,
            3L to MR.strings.on_hold,
            4L to MR.strings.dropped,
            5L to MR.strings.plan_to_read,
        )

        @JvmStatic
        fun cases() = listOf(
            StatusCase("RanobeDB") { RanobeDb(102L) },
            StatusCase("NovelList") { NovelList(101L) },
            StatusCase("NovelUpdates") { NovelUpdates(100L) },
        )
    }
}
