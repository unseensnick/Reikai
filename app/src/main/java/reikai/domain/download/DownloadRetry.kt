package reikai.domain.download

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How both downloaders retry a fetch that failed: a manga page image, a novel chapter's text. Three
 * more tries after the first, waiting 2, 4 and 8 seconds before each.
 */
object DownloadRetry {
    const val MAX_RETRIES = 3

    /** The wait before retry number [retry], counted from 0. */
    fun delayBefore(retry: Int): Duration = (2L shl retry).seconds
}
