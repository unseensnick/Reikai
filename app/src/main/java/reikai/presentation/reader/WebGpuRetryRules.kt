package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.loader.PageLoader
import eu.kanade.tachiyomi.ui.reader.model.DownloadStream
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage

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

/**
 * Queues a failed page's forced refetch (mihonapp/mihon#3770), for the viewer's download path to watch.
 * The stream comes first: the loader may start the refetch before the viewer asks for the page, and a
 * download that finds no stream shows nothing until it ends.
 */
internal fun requeueForRetry(page: ReaderPage, loader: PageLoader) {
    page.downloadStream = DownloadStream()
    loader.retryPage(page)
}

/**
 * The preview a page keeps once [shown] gives way to its error page. A streaming decode's preview is
 * the image the error page replaces and frees, so it goes with it.
 */
internal fun <T : Any> previewAfterError(preview: T?, shown: T): T? = preview.takeUnless { it === shown }
