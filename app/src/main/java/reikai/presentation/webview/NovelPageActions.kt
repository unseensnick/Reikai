package reikai.presentation.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import reikai.novel.source.NovelPageKind
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * What the in-app browser offers for a novel's page: its details and chapters when opened from the novel
 * screen, [chapterId]'s text when opened from the reader, and only what the novel's source takes. Each
 * runs to the end if the browser is left meanwhile, and the reader learns of a saved chapter from the fetcher.
 */
@Composable
fun rememberNovelPageActions(
    novelId: Long?,
    chapterId: Long? = null,
): List<WebPageAction> {
    if (novelId == null) return emptyList()
    val model = assistedMetroViewModel<NovelPageActionsViewModel, NovelPageActionsViewModel.Factory> {
        create(novelId)
    }
    val kinds by model.kinds.collectAsState()
    val runner = model.runner
    val forDetails = stringResource(MR.strings.novel_page_use_for_details)
    val forChapters = stringResource(MR.strings.novel_page_use_for_chapters)
    val forChapter = stringResource(MR.strings.novel_page_use_for_chapter)

    return buildList {
        if (chapterId == null && NovelPageKind.DETAILS in kinds) {
            add(WebPageAction(forDetails) { url, html -> runner.useForDetails(novelId, url, html) })
        }
        if (chapterId == null && NovelPageKind.CHAPTERS in kinds) {
            add(WebPageAction(forChapters) { url, html -> runner.useForChapters(novelId, url, html) })
        }
        if (chapterId != null && NovelPageKind.CHAPTER_TEXT in kinds) {
            add(WebPageAction(forChapter) { url, html -> runner.useForChapterText(chapterId, url, html) })
        }
    }
}
