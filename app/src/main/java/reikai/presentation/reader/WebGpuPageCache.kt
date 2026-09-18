package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

// The WebGPU viewer's page cache identity rule, kept out of it so it can be tested without a surface.

/**
 * Whether the viewer's cached wrapper for [cached] still stands for [requested], the page now at that
 * chapter and index. The cache is keyed by both, which a chapter loaded again keeps while its pages are
 * new: a reload, or a chapter released and opened again. The old wrapper would draw the replaced image.
 */
internal fun isCachedPageCurrent(cached: ReaderPage, requested: ReaderPage?): Boolean = cached === requested
