package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import eu.kanade.tachiyomi.ui.reader.model.ReaderChapter
import mihon.app.di.appGraph

/**
 * [ReaderTransitionPage] for the view-based viewers, in the theme of its reader-themed [context].
 */
class ReaderTransitionView @JvmOverloads constructor( // RK: split to take seedColor
    context: Context,
    attrs: AttributeSet? = null,
    // RK: cover-based theming color
    @ColorInt private val seedColor: Int? = null,
) :
    AbstractComposeView(context, attrs) {

    private var data: Data? by mutableStateOf(null)

    init {
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
    }

    fun bind(
        transition: ChapterTransition,
        downloadState: TransitionDownloadState?,
        onRetry: (ReaderChapter) -> Unit,
        onButtonPressChange: (pressed: Boolean) -> Unit = {},
        statusPadding: PaddingValues = PaddingValues(),
    ) {
        data = Data(transition, downloadState, onRetry, onButtonPressChange, statusPadding)
    }

    @Composable
    override fun Content() {
        val data = data ?: return
        LaunchedEffect(data.downloadState) { data.downloadState?.checkDisk() }
        // RK: tint the transition view from the cover color when enabled
        val themeCoverBased = context.appGraph.uiPreferences.themeCoverBased.get()
        ReaderThemedContent(context, seedColor?.let { Color(it) }.takeIf { themeCoverBased }) {
            ReaderTransitionPage(
                transition = data.transition,
                downloadState = data.downloadState,
                onRetry = data.onRetry,
                onButtonPressChange = data.onButtonPressChange,
                statusPadding = data.statusPadding,
            )
        }
    }

    private data class Data(
        val transition: ChapterTransition,
        val downloadState: TransitionDownloadState?,
        val onRetry: (ReaderChapter) -> Unit,
        val onButtonPressChange: (pressed: Boolean) -> Unit,
        val statusPadding: PaddingValues,
    )
}
