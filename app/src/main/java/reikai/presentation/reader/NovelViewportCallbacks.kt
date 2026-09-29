package reikai.presentation.reader

/**
 * What a novel viewport reports to its session and asks of its host, one contract for both renderers.
 * The provider builds one value and hands it to whichever viewport the rendering mode picks, so the two
 * cannot be wired differently.
 */
class NovelViewportCallbacks(
    /** Whether a volume key scrolls right now: the setting, and the menu being down. Which way and how
     *  far a press scrolls come from the settings pushed last. */
    val volumeKeysActive: () -> Boolean,
    /** Both carry the chapter measured, not just the number: at a boundary the reader is already in
     *  the next chapter while the model still has the previous one, and an unnamed percentage lands
     *  on whichever the model happens to hold. */
    val onProgressChanged: (chapterId: Long, percent: Int) -> Unit,
    val onProgressSettled: (chapterId: Long, percent: Int) -> Unit,
    /** The line at the top of the screen as characters into its chapter (`shownCharPrefix`), null while
     *  the chapter's text starts on screen. Sent when it changes. */
    val onTopLine: (chapterId: Long, line: Int?) -> Unit,
    val onToggleMenu: () -> Unit,
    /** Swipe-between-chapters, forward or back. */
    val onStepChapter: (forward: Boolean) -> Unit,
    /** Which chapter the reader is in, whenever that changes. The window is the only reason it can
     *  differ from the one the model last opened. */
    val onVisibleChapter: (chapterId: Long) -> Unit,
    /** The reader asking again for the chapter beyond an edge that would not load. */
    val onRetryBoundary: (forward: Boolean) -> Unit,
    /** The cutout inset in dp, which the WebView page's CSS pixels are, zero when the host already pads
     *  clear of it. Read per load and again when insets arrive, since only the window knows it. */
    val cutoutTopDp: () -> Int,
    /** Whether a chapter fits on one screen, whenever that answer changes. Such a chapter has no
     *  scroll room, so the model reads it when the reader steps forward from it. */
    val onChapterFits: (chapterId: Long, fits: Boolean) -> Unit,
    /** A chapter's last line reached the screen, once its images had landed. The model reads the
     *  novel's last chapter on it, since nothing follows that one to be left into. */
    val onChapterEndSeen: (chapterId: Long) -> Unit,
    /** A scroll the reader's finger made, in pixels; the provider hides the menu past its threshold.
     *  The viewport's own scrolls (seek, keys, read aloud, auto-scroll) never report. */
    val onReaderScrolled: (dyPx: Int) -> Unit,
)
