package eu.kanade.tachiyomi.ui.reader.viewer

import android.content.Context
import android.util.AttributeSet
import androidx.annotation.ColorInt
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.AbstractComposeView
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.reader.ChapterTransition
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.ui.reader.model.ChapterTransition
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.isLocal
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class ReaderTransitionView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    // RK: cover-based theming color (Y11)
    @ColorInt private val seedColor: Int? = null,
) :
    AbstractComposeView(context, attrs) {

    private var data: Data? by mutableStateOf(null)

    init {
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    }

    fun bind(transition: ChapterTransition, downloadManager: DownloadManager, manga: Manga?) {
        data = if (manga != null) {
            Data(
                transition = transition,
                currChapterDownloaded = transition.from.pageLoader?.isLocal == true,
                goingToChapterDownloaded = manga.isLocal() ||
                    transition.to?.chapter?.let { goingToChapter ->
                        downloadManager.isChapterDownloaded(
                            chapterName = goingToChapter.name,
                            chapterScanlator = goingToChapter.scanlator,
                            chapterUrl = goingToChapter.url,
                            mangaTitle = manga.title,
                            sourceId = manga.source,
                            skipCache = true,
                        )
                    } ?: false,
            )
        } else {
            null
        }
    }

    @Composable
    override fun Content() {
        data?.let {
            // RK: tint the transition view from the cover color when enabled (Y11)
            val themeCoverBased = Injekt.get<UiPreferences>().themeCoverBased.get()
            TachiyomiTheme(seedColor = seedColor?.let { c -> Color(c) }.takeIf { themeCoverBased }) {
                CompositionLocalProvider(
                    LocalTextStyle provides MaterialTheme.typography.bodySmall,
                    LocalContentColor provides MaterialTheme.colorScheme.onBackground,
                ) {
                    ChapterTransition(
                        transition = it.transition,
                        currChapterDownloaded = it.currChapterDownloaded,
                        goingToChapterDownloaded = it.goingToChapterDownloaded,
                    )
                }
            }
        }
    }

    private data class Data(
        val transition: ChapterTransition,
        val currChapterDownloaded: Boolean,
        val goingToChapterDownloaded: Boolean,
    )
}
