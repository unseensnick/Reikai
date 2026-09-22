package reikai.domain.novel.track

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelTrack
import tachiyomi.core.common.util.system.logcat

/**
 * Tells the novel's trackers that move back on unread ([UnreadPushTracker]) which chapters were just
 * marked unread. Off the mark itself, which a screen waits on, and on the app's lifetime rather than
 * the screen's, so leaving it does not cut the site write short.
 */
@Inject
@SingleIn(AppScope::class)
class PushNovelUnread(
    private val getNovelTracks: GetNovelTracks,
    private val trackerManager: TrackerManager,
    private val insertNovelTrack: InsertNovelTrack,
) {

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logcat(LogPriority.WARN, e) },
    )

    /** One call per unread, however many merged sources its chapters come from: a track spans them all. */
    fun launch(unread: List<NovelChapter>) {
        if (unread.isEmpty()) return
        scope.launch {
            tracksFor(unread).forEach { track ->
                val tracker = trackerManager.get(track.trackerId)
                if (tracker !is UnreadPushTracker || !tracker.isLoggedIn) return@forEach
                try {
                    tracker.pushUnread(track.toDbTrack(), unread)
                        ?.let { insertNovelTrack.await(it.toNovelTrack(idRequired = true)!!) }
                } catch (e: Exception) {
                    logcat(LogPriority.WARN, e) { "Could not move ${tracker.name} back for unread chapters" }
                }
            }
        }
    }

    internal suspend fun tracksFor(unread: List<NovelChapter>): List<NovelTrack> =
        unread.map { it.novelId }.distinct().flatMap { getNovelTracks.awaitGroup(it) }.distinctBy { it.id }
}
