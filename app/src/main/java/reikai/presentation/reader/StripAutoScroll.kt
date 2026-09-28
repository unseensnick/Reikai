package reikai.presentation.reader

import eu.kanade.tachiyomi.source.model.Page

/**
 * Whether a long strip's smooth auto-scroll holds, over the states of the pages from the one on screen
 * to the next below it. Only a load in flight holds: a failed page scrolls into view, where its Retry
 * is, so one bad page never freezes the strip. Both strip viewers ask this, so they cannot disagree.
 */
internal fun holdsStripAutoScroll(ahead: List<Page.State>): Boolean = ahead.any { it.isLoading }

private val Page.State.isLoading: Boolean
    get() = when (this) {
        Page.State.Queue, Page.State.LoadPage, Page.State.DownloadImage -> true
        Page.State.Ready, is Page.State.Error -> false
    }
