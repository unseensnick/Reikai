package reikai.domain.download

import eu.kanade.tachiyomi.data.download.Downloader
import reikai.storage.PARKED_PREFIX
import kotlin.time.Duration.Companion.hours

/**
 * The rules both download indexes follow, the manga `DownloadCache` and the novel one: when an index
 * is old enough to check against the disk again, and which names a scan counts. A write in the app
 * updates an index at once, so the rescan only catches changes made outside it.
 */
object DownloadIndexRules {

    /** Never rescan on a timer; only Reindex downloads, a storage move or a restore rescans. */
    const val MANUAL = -1

    /** An index never scanned (or thrown away, which resets `lastRenew` to 0) is stale whatever the interval. */
    fun isStale(lastRenew: Long, now: Long, intervalHours: Int): Boolean = when {
        lastRenew == 0L -> true
        intervalHours == MANUAL -> false
        else -> now - lastRenew > intervalHours.hours.inWholeMilliseconds
    }

    /**
     * A half-written chapter is never counted, nor is a chapter folder a merge copied on and set aside, which would
     * read as a second download of its chapter. The manga downloader marks a file in flight with upstream's
     * suffix, the novel writer and the merge with the storage gateway's; DownloadIndexRulesTest holds them equal.
     */
    fun isIndexed(name: String): Boolean = !name.endsWith(Downloader.TMP_DIR_SUFFIX) && !name.startsWith(PARKED_PREFIX)
}
