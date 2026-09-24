package reikai.domain.recents

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlin.time.Clock

/**
 * The bound on the Reikai-owned recents feeds (novel updates and both newly-added lanes): three months
 * back, at most this many rows. Mihon's manga updates feed writes the same bound as literals, left in
 * place so its file stays upstream's.
 */
const val RECENTS_FEED_LIMIT = 500L
private const val RECENTS_FEED_MONTHS = 3L

/** Recomputed per call, so a long-running process keeps a window from now rather than from startup. */
fun recentsFeedCutoff(): Long = Clock.System.now()
    .minus(RECENTS_FEED_MONTHS, DateTimeUnit.MONTH, TimeZone.currentSystemDefault())
    .toEpochMilliseconds()
