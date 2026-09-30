package reikai.domain.download

import eu.kanade.tachiyomi.data.download.DownloadManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.sample
import kotlin.time.Duration.Companion.milliseconds

// Page progress moves many times a second, and a row's spinner cannot show that detail.
private val PROGRESS_SAMPLE = 500.milliseconds

/**
 * Emits when a queued manga download changes status or, sampled, page progress. Mihon's queue re-emits
 * for neither: a failed download stays in it, so a list drawn from the queue alone kept showing the
 * chapter as downloading. Novels need no twin, since their queue holds each state as a value.
 */
fun DownloadManager.queuedDownloadChanges(): Flow<Unit> = merge(
    statusFlow().map { },
    progressFlow().map { }.sample(PROGRESS_SAMPLE),
)
