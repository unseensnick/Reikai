package eu.kanade.tachiyomi.ui.reader.viewer.webgpu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import ca.mpreg.webgpuviewer.viewer.ComposePageSize
import ca.mpreg.webgpuviewer.viewer.ImagePage
import eu.kanade.presentation.reader.ChapterTransition
import eu.kanade.presentation.reader.ReaderError
import eu.kanade.presentation.util.formattedMessage
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderThemedContent
import eu.kanade.tachiyomi.ui.reader.viewer.transitionDownloadState
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import reikai.presentation.reader.NeighbourChapterRetry
import reikai.presentation.reader.STRIP_CAPTURE_SCALE
import reikai.presentation.reader.keepTallestHeight
import reikai.presentation.reader.stripCaptureSize
import tachiyomi.presentation.core.components.CombinedCircularProgressIndicator

/** The side padding `PagerTransitionHolder` gave the same transition view. */
private const val TRANSITION_SIDE_PADDING = 64

/** The padding `WebtoonTransitionHolder` gave it, which took only the height it needed. */
private const val STRIP_TRANSITION_SIDE_PADDING = 32
private const val STRIP_TRANSITION_VERTICAL_PADDING = 128

/** How much of the viewport `WebtoonPageHolder` gave its error layout. */
private const val STRIP_ERROR_HEIGHT_FRACTION = 0.8f

/** The top margin `WebtoonPageHolder` gave its spinner, as a fraction of the viewport. */
private const val STRIP_PROGRESS_TOP_FRACTION = 0.25f

// RK: a page can be built before the viewer has laid out, and its size is fixed at construction
private fun WebGpuViewer.stripCapture(heightFraction: Float = 1f): ComposePageSize {
    val metrics = activity.resources.displayMetrics
    return stripCaptureSize(
        viewerState.width.takeIf { it > 0 } ?: metrics.widthPixels,
        viewerState.height.takeIf { it > 0 } ?: metrics.heightPixels,
        heightFraction,
    )
}

/**
 * The reader's own chapter and page chrome, as composables, in the reader's theme.
 *
 * Built on the reader background, which is fixed for the page's life: a theme change rebuilds every
 * page (see [WebGpuViewer]'s image property listener).
 */
abstract class ReaderComposePage(
    protected val viewer: WebGpuViewer,
    size: ComposePageSize = ComposePageSize.Auto,
) : ImagePage.Compose(size, viewer.readerBackgroundColor()) {

    /**
     * The theme goes on here because the library's page knows nothing of the app's colours.
     */
    @Composable
    final override fun BoxScope.Content() {
        // RK --> a strip's smaller capture draws at the smaller density, so text and ring keep their size
        val density = LocalDensity.current
        val drawn = if (size is ComposePageSize.Of) {
            Density(density.density / STRIP_CAPTURE_SCALE, density.fontScale)
        } else {
            density
        }
        // The cover seed colour, as the other viewers theme their transition and progress views
        CompositionLocalProvider(LocalDensity provides drawn) {
            ReaderThemedContent(viewer.readerThemedContext, viewer.coverSeedColor?.let(::Color)) { PageContent() }
        }
        // RK <--
    }

    @Composable
    protected abstract fun PageContent()
}

/**
 * A page that failed to load or decode, with the pager's error layout: the message, Retry, and
 * Open in WebView for an image that came off the web.
 */
class ErrorPage internal constructor(
    viewer: WebGpuViewer,
    private val viewerPage: WebGpuViewer.ViewerReaderPage?,
    private val error: Throwable?,
) : ReaderComposePage(
    viewer,
    // WebtoonPageHolder gave its error layout less than a screen, so the page below shows through.
    // RK: at a smaller capture, see stripCaptureSize
    if (viewer is WebGpuViewerContinuous) viewer.stripCapture(STRIP_ERROR_HEIGHT_FRACTION) else ComposePageSize.Auto,
) {

    @Composable
    override fun PageContent() {
        val context = LocalContext.current
        // As the pager's own error layout: only offered for an image that came off the web.
        val imageUrl = viewerPage?.page?.imageUrl?.takeIf { it.startsWith("http", ignoreCase = true) }
        val strip = if (viewer is WebGpuViewerContinuous) {
            Modifier.fillMaxHeight().wrapContentHeight() // RK: the capture is already that height
        } else {
            Modifier
        }

        ReaderError(
            modifier = strip,
            message = with(context) { error?.formattedMessage },
            onRetry = { viewerPage?.let { viewer.retryLoad(it) } },
            onOpenInWebView = imageUrl?.let {
                {
                    val sourceId = viewer.activity.viewModel.manga?.source
                    context.startActivity(WebViewActivity.newIntent(context, it, sourceId))
                }
            },
        )
    }
}

/** A page still downloading: the reader's progress ring. */
class ProgressPage(viewer: WebGpuViewer) : ReaderComposePage(
    viewer,
    if (viewer is WebGpuViewerContinuous) viewer.stripCapture() else ComposePageSize.Auto, // RK: see stripCaptureSize
) {

    /**
     * 0 to 1. On the page, not in the composition, which is dropped when the page goes out of
     * reach. Written from the loader's collector; snapshot state takes writes from any thread.
     */
    var progress: Float
        get() = progressState
        set(value) {
            progressState = value.fastCoerceIn(0f, 1f)
        }

    private var progressState by mutableFloatStateOf(0f)

    @Composable
    override fun PageContent() {
        if (viewer !is WebGpuViewerContinuous) {
            CombinedCircularProgressIndicator(progress = { progress })
            return
        }
        // WebtoonPageHolder centred its spinner below a quarter-height top margin, which puts it
        // at five eighths of the slot rather than halfway down.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = maxHeight * STRIP_PROGRESS_TOP_FRACTION),
                contentAlignment = Alignment.Center,
            ) {
                CombinedCircularProgressIndicator(progress = { progress })
            }
        }
    }
}

/** The page between two chapters, or before the first and after the last. */
class TransitionPage(
    viewer: WebGpuViewer,
    val prevChapter: ReaderChapter?,
    val nextChapter: ReaderChapter?,
) : ReaderComposePage(viewer, ComposePageSize.Wrap) {

    /** As the pager and webtoon adapters framed it: leaving [prevChapter] for [nextChapter]. */
    private val transition: ChapterTransition? = when {
        prevChapter != null -> ChapterTransition.Next(prevChapter, nextChapter)
        nextChapter != null -> ChapterTransition.Prev(nextChapter, null)
        else -> null
    }

    @Composable
    override fun PageContent() {
        val transition = transition ?: return
        // RK: the state the other viewers bind, so the badge is right on the first frame
        val downloadState = remember(transition) { viewer.activity.viewModel.transitionDownloadState(transition) }

        CompositionLocalProvider(
            LocalTextStyle provides MaterialTheme.typography.bodySmall,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            // The padding the holder each viewer used to inflate this into gave it.
            val padding = if (viewer is WebGpuViewerContinuous) {
                Modifier.padding(
                    horizontal = STRIP_TRANSITION_SIDE_PADDING.dp,
                    vertical = STRIP_TRANSITION_VERTICAL_PADDING.dp,
                )
            } else {
                Modifier.padding(horizontal = TRANSITION_SIDE_PADDING.dp)
            }
            Box(modifier = padding.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                // RK --> a neighbour chapter that failed to load shows why, with its Retry
                Column(
                    modifier = Modifier.keepTallestHeight(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ChapterTransition(
                        transition = transition,
                        currChapterDownloaded = downloadState.currChapterDownloaded,
                        goingToChapterDownloaded = downloadState.goingToChapterDownloaded,
                    )
                    NeighbourChapterRetry(prevChapter, nextChapter, onRetry = viewer::retryChapter)
                }
                // RK <--
            }
        }
    }

    // RK: isDownloaded removed; the badge reads ReaderViewModel.isChapterDownloaded instead
}
