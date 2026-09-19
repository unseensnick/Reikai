package reikai.domain.download

import eu.kanade.tachiyomi.data.download.Downloader
import kotlin.time.Duration.Companion.hours

/**
 * The rules both download indexes follow, the manga `DownloadCache` and the novel one: when an index
 * is old enough to check against the disk again, and which names a scan counts. A write in the app
 * updates an index at once, so the rescan only catches changes made outside it.
 */
object DownloadIndexRules {

    val RENEW_INTERVAL_MS = 1.hours.inWholeMilliseconds

    fun isStale(lastRenew: Long, now: Long): Boolean = now - lastRenew > RENEW_INTERVAL_MS

    /** A half-written chapter, which both downloaders mark with the same suffix, is never counted. */
    fun isIndexed(name: String): Boolean = !name.endsWith(Downloader.TMP_DIR_SUFFIX)
}
