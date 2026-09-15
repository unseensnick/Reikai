package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter

// The WebGPU viewer's retry rules, kept out of it so they can be tested without a surface.

/**
 * Whether reaching a neighbour's edge starts loading it. A neighbour that failed is held until the
 * next page turn or a Retry tap: the viewer asks on every frame, so it used to fetch every 5 seconds.
 */
internal fun shouldAutoPreload(state: ReaderChapter.State, isHeld: Boolean): Boolean =
    state !is ReaderChapter.State.Loaded && !(state is ReaderChapter.State.Error && isHeld)

/** Whether the edge leads to a transition page. A failed neighbour has no pages, so only one can offer its Retry. */
internal fun showsTransition(alwaysShowChapterTransition: Boolean, neighbour: ReaderChapter.State): Boolean =
    alwaysShowChapterTransition || neighbour is ReaderChapter.State.Error

/** The chapter a transition page offers to retry: whichever side failed, the next one first. */
internal fun chapterToRetry(prevChapter: ReaderChapter?, nextChapter: ReaderChapter?): ReaderChapter? =
    listOfNotNull(nextChapter, prevChapter).firstOrNull { it.state is ReaderChapter.State.Error }
