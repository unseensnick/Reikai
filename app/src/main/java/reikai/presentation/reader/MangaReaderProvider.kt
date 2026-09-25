package reikai.presentation.reader

import android.content.Context
import android.content.Intent
import eu.kanade.domain.manga.model.readerOrientation
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.chapter.ReaderChapterItem
import eu.kanade.tachiyomi.ui.reader.model.ReaderPage
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import eu.kanade.tachiyomi.util.system.readerBackgroundColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.sample
import reikai.data.coil.extractCoverColor
import reikai.data.coil.seedColor
import reikai.domain.download.downloadStateOf
import reikai.domain.entry.EntryId
import reikai.domain.merge.GroupChapterFlags
import reikai.presentation.components.chapterSubtitle
import reikai.presentation.components.pageProgressLabel
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.asMangaCover
import kotlin.time.Duration.Companion.milliseconds

/**
 * Manga's answers, over the live [ReaderViewModel] the host already resolved. It stays Mihon's and
 * stays synced; this only adapts it.
 */
class MangaReaderProvider(
    private val viewModel: ReaderViewModel,
    private val readerPreferences: ReaderPreferences,
    private val downloadManager: DownloadManager,
    private val titleWords: ChapterTitleWords,
) : ReaderProvider {

    // The visible chapter rather than the active one: they differ mid-scroll across a boundary, and
    // pairing a title from one with a page count from the other is the chrome tear.
    override val chrome: Flow<ReaderChromeState> = combine(
        viewModel.state,
        readerPreferences.chapterTitleFormat.changes(),
    ) { state, format ->
        val chapter = state.visibleChapter?.chapter
        ReaderChromeState(
            state.manga?.title,
            chapter?.let { format.chapterTitle(it.name, it.chapter_number.toDouble(), titleWords) },
        )
    }

    override val bottomButtons: Flow<List<ReaderBottomButton>> =
        ReaderBottomButton.orderedChanges(ReaderBottomButton.BarPreferences.manga(readerPreferences))

    override val bottomButtonScope = ReaderBottomButton.Scope.Manga

    override fun seedColor(context: Context): Flow<Int?> = viewModel.state
        .mapNotNull { it.manga }
        .distinctUntilChangedBy { it.id }
        .map { manga -> EntryId.Manga(manga.id).seedColor { context.extractCoverColor(manga.asMangaCover()) } }

    override val displayFilters = ReaderDisplayFilters(
        customBrightness = readerPreferences.customBrightness,
        customBrightnessValue = readerPreferences.customBrightnessValue,
        colorFilter = readerPreferences.colorFilter,
        colorFilterValue = readerPreferences.colorFilterValue,
        colorFilterMode = readerPreferences.colorFilterMode,
        grayscale = readerPreferences.grayscale,
        invertedColors = readerPreferences.invertedColors,
    )

    override val fullscreen = readerPreferences.fullscreen

    override val drawUnderCutout = readerPreferences.drawUnderCutout

    override fun onReaderMoved() = Unit

    // Each page turn writes its own position, so nothing is held back.
    override fun flushPosition() = Unit

    // The source id lets the browser reuse that source's headers.
    override suspend fun chapterWebViewIntent(context: Context, url: String, title: String?, chapterId: Long): Intent =
        WebViewActivity.newIntent(context, url, viewModel.getSource(chapterId)?.id, title)

    // The source builds the URL and an extension may override that, so it is not main-thread work.
    override suspend fun chapterWebUrl(chapterId: Long): String? = withIOContext { viewModel.getChapterUrl(chapterId) }

    // A manga chapter's browser offers nothing to save.

    // Upstream's ReaderActivity.openMangaScreen, moved here so the host asks the session.
    override fun detailsIntent(context: Context): Intent? = viewModel.manga?.id?.let { id ->
        Intent(context, MainActivity::class.java).apply {
            action = Constants.SHORTCUT_MANGA
            putExtra(Constants.MANGA_EXTRA, id)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    override fun pageBackground(context: Context): Flow<Int> =
        readerPreferences.readerTheme.changes().map(context::readerBackgroundColor)

    override val navigator: Flow<ReaderNavigatorState> = combine(
        viewModel.state,
        readerPreferences.verticalNavigator.changes(),
        readerPreferences.verticalNavigatorOnLeft.changes(),
        readerPreferences.verticalNavigatorHeight.changes(),
        readerPreferences.showNavigator.changes(),
    ) { state, railModes, onLeft, height, show ->
        ReaderNavigatorState(
            progress = state.position?.progress,
            shape = when {
                !show -> ReaderNavigatorShape.None
                // The resolved mode, so a series on auto-webtoon gets the rail its actual mode asks for.
                ReadingMode.fromPreference(viewModel.getMangaReadingMode()) in railModes -> ReaderNavigatorShape.Rail
                else -> ReaderNavigatorShape.Slider
            },
            railOnLeft = onLeft,
            railHeightPercent = height,
            hasPrevious = state.viewerChapters?.prevChapter != null,
            hasNext = state.viewerChapters?.nextChapter != null,
        )
    }

    override val showProgress: Flow<Boolean> = readerPreferences.showPageNumber.changes()

    // A step, a pick or a reload that failed is reported, and stays so until the next one starts. A
    // first open that failed is upstream's initError, reported the same way with nothing to keep
    // reading, as a novel's is, so it gets the chapter's page and Retry rather than closing on a toast.
    // A neighbour failing to preload keeps upstream's retry on its transition page and reports nothing.
    override val loadState: Flow<ReaderLoadState> = viewModel.state.map { state ->
        val failure = state.adjacentLoadFailure
        val initError = state.initError
        when {
            initError != null -> ReaderLoadState.Failed(
                initError.message,
                canKeepReading = false,
                // No chapter without the series, which is also what resolves the chapter's page.
                chapterId = viewModel.openingChapterId.takeIf { state.manga != null },
                attempt = attemptOf(initError),
            )
            state.isLoadingAdjacentChapter -> ReaderLoadState.Loading
            failure != null -> ReaderLoadState.Failed(
                failure.message,
                canKeepReading = state.currentChapter != null,
                chapterId = failure.chapterId,
                attempt = failure.attempt,
            )
            else -> ReaderLoadState.Idle
        }
    }

    // The chapter that failed, re-opened through the sheet's own path, which resolves against the live
    // chapter list rather than the reader's copy. Retrying a pick goes through the engine instead, and
    // a reload from the source is retried as one, since the sheet's path would serve a downloaded copy.
    override fun retryLoad() {
        val state = viewModel.state.value
        if (state.initError != null) return viewModel.retryInit()
        if (state.adjacentLoadFailure?.fromSource == true) return viewModel.reloadChapter(fromSource = true)
        val id = state.adjacentLoadFailure?.chapterId ?: state.currentChapter?.chapter?.id ?: return
        chapterList.open(id)
    }

    override fun reloadChapter(fromSource: Boolean) = viewModel.reloadChapter(fromSource)

    // One attempt per error: the state re-emits for unrelated changes, and a fresh stamp each time
    // would raise a dismissed failure again.
    private var initFailure: Pair<Throwable, Long>? = null

    private fun attemptOf(error: Throwable): Long = initFailure?.takeIf { it.first === error }?.second
        ?: ReaderLoadState.Failed.nextAttempt().also { initFailure = error to it }

    override val bookmarked: Flow<Boolean> = viewModel.state.map { it.bookmarked }

    override fun toggleBookmark() = viewModel.toggleChapterBookmark()

    // Re-read per chapter rather than per state emission: the source builds the URL, and the chapter
    // is the only thing about it that changes while a session runs.
    override val webUrl: Flow<String?> = viewModel.state
        .map { it.viewerChapters?.currChapter?.chapter?.id }
        .distinctUntilChanged()
        .map { viewModel.getChapterUrl() }
        // The source builds the URL and an extension may override that, so it is not main-thread work.
        // Upstream resolves it in launchIO for the same reason (ReaderActivity, assistUrl).
        .flowOn(Dispatchers.IO)

    override suspend fun updateHistory() = viewModel.updateHistory()

    override fun restartReadTimer() = viewModel.restartReadTimer()

    override fun onActivityFinish() = viewModel.onActivityFinish()

    override suspend fun previousChapter() = viewModel.loadPreviousChapter()

    override suspend fun nextChapter() = viewModel.loadNextChapter()

    override val chapterList: ReaderChapterList = object : ReaderChapterList {

        /**
         * The disk check is the expensive half (a folder-name hash per chapter), so it runs once per
         * queue change, which is also when a finished download leaves the queue. Only the progress
         * numbers refresh on the sampled tick, and they are read off the live queue entries. A row
         * shows the merge group's read, bookmarked and on-disk state, as the details list does.
         */
        override val rows: Flow<List<ReaderChapterRow>> = downloadManager.queueState
            .flatMapLatest { queue ->
                val pageCounts = viewModel.sheetPageCounts()
                val chapters = viewModel.getChapters()
                    .map { it.copy(chapter = it.chapter.copy(pageCount = pageCounts[it.chapter.id] ?: 0L)) }
                val queued = queue.associateBy { it.chapter.id }
                val flags = viewModel.sheetFlags(chapters.map { it.chapter })
                val build = { chapters.map { it.toReaderChapterRow(queued, flags, titleWords) } }
                if (queued.isEmpty()) {
                    flowOf(build())
                } else {
                    downloadManager.progressFlow().sample(PROGRESS_SAMPLE).map { build() }.onStart { emit(build()) }
                }
            }
            .flowOn(Dispatchers.IO)

        override val currentChapterId: Flow<Long> =
            viewModel.state.map { it.currentChapter?.chapter?.id ?: -1L }

        override fun open(chapterId: Long) {
            viewModel.getChapters().find { it.chapter.id == chapterId }
                ?.let { viewModel.loadNewChapterFromDialog(it.chapter) }
        }

        override fun setRead(chapterId: Long, read: Boolean) {
            chapterOf(chapterId)?.let { viewModel.setChapterReadStatus(it, read) }
        }

        override fun setBookmark(chapterId: Long, bookmarked: Boolean) =
            viewModel.toggleBookmark(chapterId, bookmarked)

        override fun download(chapterId: Long, action: ChapterDownloadAction) {
            chapterOf(chapterId)?.let { viewModel.handleChapterDownload(it, action) }
        }

        private fun chapterOf(chapterId: Long) =
            viewModel.getChapters().find { it.chapter.id == chapterId }?.chapter
    }

    // A manga page is an image the source ships, so there is no text for a size or a page colour to
    // act on. The buttons are absent rather than shown doing nothing.
    override val textSettings: ReaderTextSettings? = null

    // Never offered for manga, so the button is absent rather than doing nothing. Whether an image
    // viewer should scroll itself is a reading-mode question those viewers own, and it has not been
    // asked; this stays the novel session's until it is.
    override val autoScroll: ReaderAutoScroll? = null

    // Same reason as the typography above: an image has no words whose openings could be bolded.
    override val bionicReading: ReaderBionicReading? = null

    // A manga page is an image, so there is no text to read aloud.
    override val readAloud: ReaderReadAloud? = null

    // Unresolved, because the picker's "use default" action has to be able to tell a series following
    // the default from one pinned to the same value the default happens to be.
    override val orientation: Flow<Int> = viewModel.state
        .map { it.manga?.readerOrientation?.toInt() ?: ReaderOrientation.DEFAULT.flagValue }

    override val keepScreenOn: Flow<Boolean> = readerPreferences.keepScreenOn.changes()

    override fun setOrientation(flagValue: Int) =
        viewModel.setMangaOrientationType(ReaderOrientation.fromPreference(flagValue))

    override fun setKeepScreenOn(enabled: Boolean) = readerPreferences.keepScreenOn.set(enabled)

    // Resolved rather than read off the stored flag, because auto-webtoon overrides a series to long
    // strip at open time without writing the preference.
    override fun createViewport(host: ReaderActivity): ReaderViewport =
        MangaViewport(
            viewer = ReadingMode.toViewer(viewModel.getMangaReadingMode(), host),
            // A scrub is drawn from the visible chapter's page count, so it resolves its target there
            // too. Resolving it against the active chapter paired one chapter's index with another's
            // list for as long as the model took to swap, which is the tear the chrome comment names.
            visibleChapter = { viewModel.state.value.visibleChapter },
            activeChapter = { viewModel.state.value.currentChapter },
        )

    // Nothing to wire at creation: Mihon's updateViewer builds manga's viewport once the manga arrives in
    // state, and setChapters feeds it, which the host keeps as upstream has them.
    override fun attach(host: ReaderActivity) = Unit

    // The image viewers hold nothing of the session's beyond the host, which goes with them.
    override fun detach(viewport: ReaderViewport) = Unit

    /**
     * Binds the page-action verbs to one page, so the dialog carries a capability rather than a
     * manga type and the verbs need no argument. Novels build no equivalent, which is what makes
     * that dialog unreachable for them instead of present and dead.
     */
    fun pageActions(page: ReaderPage): ReaderPageActions = object : ReaderPageActions {
        override fun save() = viewModel.saveImage(page)

        override fun share(copyToClipboard: Boolean) = viewModel.shareImage(page, copyToClipboard)

        override fun setAsCover() = viewModel.setAsCover(page)
    }
}

/** How often a running download refreshes the sheet. Rebuilding the whole list on every reported frame
 *  would recompose it many times a second for a spinner that cannot show that detail. */
private val PROGRESS_SAMPLE = 500.milliseconds

/** A chapter sheet's row, as the merge group's [flags] answer for it. */
internal fun ReaderChapterItem.toReaderChapterRow(
    queued: Map<Long, Download>,
    flags: GroupChapterFlags<Chapter>,
    words: ChapterTitleWords,
): ReaderChapterRow {
    val active = queued[chapter.id]
    return ReaderChapterRow(
        id = chapter.id,
        title = chapter.name,
        subtitle = chapterSubtitle(sourceName, chapter.scanlator),
        dateUpload = chapter.dateUpload,
        // Mihon has no reader chapter sheet; this follows its details list, which shows the page.
        readProgress = pageProgressLabel(chapter.lastPageRead, chapter.pageCount)
            ?.let { (resource, args) -> words.pageProgress(resource, args) },
        read = flags.isRead(chapter),
        bookmark = flags.isBookmarked(chapter),
        downloadState = downloadStateOf(active?.status) { flags.isDownloaded(chapter) },
        downloadProgress = active?.progress ?: 0,
    )
}
