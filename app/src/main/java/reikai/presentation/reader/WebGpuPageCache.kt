package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import kotlinx.coroutines.Job

// The WebGPU viewer's page cache identity rules, kept out of it so they can be tested without a surface.

/**
 * Whether the viewer's cached wrapper for [cached] still stands for [requested], the page now at that
 * chapter and index. The cache is keyed by both, which a chapter loaded again keeps while its pages are
 * new: a reload, or a chapter released and opened again. The old wrapper would draw the replaced image.
 */
internal fun isCachedPageCurrent(cached: ReaderPage, requested: ReaderPage?): Boolean = cached === requested

/**
 * Stops the download and the status watcher of a wrapper the cache dropped as stale. Both are keyed like
 * the cache, so the page replacing it inherits the key: left running, they load and watch the old page.
 */
internal fun <K> cancelPageJobs(key: K, loadJobs: MutableMap<K, Job>, watchJobs: MutableMap<K, Job>) {
    loadJobs.remove(key)?.cancel()
    watchJobs.remove(key)?.cancel()
}
