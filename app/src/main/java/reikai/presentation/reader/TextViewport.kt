package reikai.presentation.reader

import reikai.presentation.novel.reader.NovelReaderSettings

/**
 * What a viewport answers when it renders text rather than images, so the host drives the WebView
 * and the native renderer the same way. Kept off [ReaderViewport] so an image viewer is
 * never made to declare a contract it has no answer for.
 *
 * How a chapter becomes pixels is the implementation's business, which is why [load] takes the
 * chapter the model produced rather than a document built for one renderer.
 */
interface TextViewport {

    /**
     * Starts over on [chapter] alone, at its [NovelReaderViewModel.LoadedChapter.progressPercent].
     * Once this returns, nothing the renderer reports is about the chapters it held before: the host
     * tells the model so, and the model takes the renderer's word again from there.
     *
     * Suspending because building the document is proportional to the chapter, and a downloaded one
     * carries its images inline. Implementations do that work off the main thread.
     */
    suspend fun load(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings)

    /**
     * Applies changed display settings to what is already rendered, so a size or colour change lands
     * in place rather than waiting for the next chapter.
     */
    fun applySettings(settings: NovelReaderSettings)

    /**
     * Runs or stops the continuous scroll at [pixelsPerFrame], the speed the user set. The host
     * decides rather than the renderer, because auto-scroll pauses while the chrome is showing and
     * only the host knows that it is.
     */
    fun setAutoScroll(running: Boolean, pixelsPerFrame: Float)

    /** How this renderer holds more than one chapter, which is how the host grows it across a seam. */
    val window: ChapterWindow
}

/**
 * Growing and shrinking the set of chapters a renderer holds, so reading crosses a chapter boundary
 * without a load. Additive rather than a whole-window `load`, because the renderer keeps each
 * chapter's rendered text and a replacement would throw it away.
 *
 * Every call happens after [TextViewport.load] has established the chapter these sit around.
 */
interface ChapterWindow {

    /** Adds [chapter] below what is showing. Suspending for the same reason [TextViewport.load] is. */
    suspend fun append(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings)

    /** Adds [chapter] above what is showing, without moving the reader. */
    suspend fun prepend(chapter: NovelReaderViewModel.LoadedChapter, settings: NovelReaderSettings)

    /** Drops a chapter the window has moved past, freeing its rendered text. */
    fun evict(chapterId: Long)

    /**
     * Why the window stops where it does at each end, or null at an end that simply has no more
     * chapters. Separate from the verbs above because a chapter that would not load never becomes an
     * item, so there is nothing for the reader to reach except the edge itself.
     */
    fun setBoundaryFailures(
        previous: NovelReaderViewModel.BoundaryFailure?,
        next: NovelReaderViewModel.BoundaryFailure?,
    )
}
