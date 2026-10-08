package reikai.data.track

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.track.Tracker
import tachiyomi.i18n.MR

/**
 * The status vocabulary RanobeDB, NovelList and NovelUpdates share: the values each stores and the
 * label each shows. A tracker that cannot store one of them leaves it out of its status list.
 */
object NovelTrackerStatuses {
    const val READING = 1L
    const val COMPLETED = 2L
    const val ON_HOLD = 3L
    const val DROPPED = 4L
    const val PLAN_TO_READ = 5L

    val ALL = listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ)

    fun label(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        DROPPED -> MR.strings.dropped
        PLAN_TO_READ -> MR.strings.plan_to_read
        else -> null
    }

    /** Where a bind files a novel the site's list does not hold yet: by whether anything was read. */
    fun unlisted(hasReadChapters: Boolean): Long = if (hasReadChapters) READING else PLAN_TO_READ
}

/**
 * A tracker speaking [NovelTrackerStatuses]. None of these sites stores a reread, so a reread stays
 * Reading rather than inventing a state; a status left out of [getStatusList] has no label either.
 */
interface NovelStatusTracker : Tracker {
    override fun getStatusList(): List<Long> = NovelTrackerStatuses.ALL

    override fun getStatus(status: Long): StringResource? =
        status.takeIf { it in getStatusList() }?.let(NovelTrackerStatuses::label)

    override fun getReadingStatus(): Long = NovelTrackerStatuses.READING

    override fun getRereadingStatus(): Long = NovelTrackerStatuses.READING

    override fun getCompletionStatus(): Long = NovelTrackerStatuses.COMPLETED
}
