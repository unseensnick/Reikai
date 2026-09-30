package reikai.domain.novel.track

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.domain.track.store.DelayedTrackingStore

/**
 * The novel tracking queue: Mihon's store over its own file, because the queue is keyed by track id
 * and novel and manga track ids share one id space, so a shared file would collide.
 */
@Inject
@SingleIn(AppScope::class)
class NovelDelayedTrackingStore(context: Context) : DelayedTrackingStore(context, "novel_tracking_queue")
