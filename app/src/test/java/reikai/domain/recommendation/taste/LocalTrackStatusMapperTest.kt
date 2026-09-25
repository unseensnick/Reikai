package reikai.domain.recommendation.taste

import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.kavita.Kavita
import eu.kanade.tachiyomi.data.track.komga.Komga
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.data.track.novellist.NovelList
import eu.kanade.tachiyomi.data.track.novelupdates.NovelUpdates
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import eu.kanade.tachiyomi.data.track.suwayomi.Suwayomi
import exh.md.utils.FollowStatus
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkClass
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tachiyomi.domain.track.model.Track
import kotlin.reflect.KClass

/**
 * Every manga tracker's statuses reach a meaning the hide-by-status toggles can act on, so a tracker
 * added later that falls through to UNKNOWN fails here rather than silently hiding nothing.
 */
class LocalTrackStatusMapperTest {

    @ParameterizedTest(name = "{0} status {2}")
    @MethodSource("statuses")
    fun `every manga tracker status has a reading meaning`(
        name: String,
        trackerId: Long,
        status: Long,
        meaningful: Boolean,
    ) {
        val mapped = LocalTrackStatusMapper(trackerManager).map(track(trackerId, status))

        (mapped != TrackStatus.UNKNOWN) shouldBe meaningful
    }

    @Test
    fun `a MangaUpdates Unfinished list entry counts as dropped`() {
        val tracker = bareTrackers.getValue(MangaUpdates::class)

        LocalTrackStatusMapper(trackerManager).map(track(tracker.id, MangaUpdates.UNFINISHED_LIST)) shouldBe
            TrackStatus.DROPPED
    }

    @Test
    fun `a MangaBaka Considering entry counts as plan to read`() {
        val tracker = bareTrackers.getValue(MangaBaka::class)

        LocalTrackStatusMapper(trackerManager).map(track(tracker.id, MangaBaka.CONSIDERING)) shouldBe
            TrackStatus.PLAN_TO_READ
    }

    private fun track(trackerId: Long, status: Long) = Track(
        id = 1L, mangaId = 1L, trackerId = trackerId, remoteId = 1L, libraryId = null, title = "",
        lastChapterRead = 0.0, totalChapters = 0L, status = status, score = 0.0, remoteUrl = "",
        startDate = 0L, finishDate = 0L, private = false,
    )

    companion object {
        // Read off TrackerManager's fields rather than built through it, since building one needs the app graph.
        private val trackerClasses = TrackerManager::class.java.declaredFields
            .map { it.type }
            .filter { Tracker::class.java.isAssignableFrom(it) }
            .map { it.kotlin }

        private val bareTrackers: Map<KClass<*>, BaseTracker> = trackerClasses.withIndex().associate { (index, type) ->
            type to statusesOf(type, id = index + 1L)
        }

        private val trackerManager = mockk<TrackerManager> {
            every { this@mockk.get(any<Long>()) } answers
                { bareTrackers.values.firstOrNull { it.id == firstArg<Long>() } }
        }

        private val novelTrackers = setOf(NovelUpdates::class, NovelList::class, RanobeDb::class)

        // A self-hosted server's unread and an unfollowed MDList entry say nothing about reading.
        private val noReadingMeaning = setOf(
            Komga::class to Komga.UNREAD,
            Kavita::class to Kavita.UNREAD,
            Suwayomi::class to Suwayomi.UNREAD,
            MdList::class to FollowStatus.UNFOLLOWED.long,
        )

        /** The tracker's real status methods on a bare instance, whose constructor would reach the graph. */
        @Suppress("UNCHECKED_CAST")
        private fun statusesOf(type: KClass<*>, id: Long): BaseTracker = mockkClass(type as KClass<BaseTracker>) {
            every { this@mockkClass.id } returns id
            every { getStatusList() } answers { callOriginal() }
            every { getStatus(any()) } answers { callOriginal() }
            every { getReadingStatus() } answers { callOriginal() }
            every { getRereadingStatus() } answers { callOriginal() }
            every { getCompletionStatus() } answers { callOriginal() }
        }

        @JvmStatic
        fun statuses() = bareTrackers
            .filterKeys { it !in novelTrackers }
            .flatMap { (type, tracker) ->
                tracker.getStatusList().map {
                    Arguments.of(type.simpleName, tracker.id, it, (type to it) !in noReadingMeaning)
                }
            }
    }
}
