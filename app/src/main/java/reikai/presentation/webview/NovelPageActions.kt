package reikai.presentation.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import mihon.app.di.appGraph
import reikai.novel.source.NovelPageKind
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * What the in-app browser offers for a novel's page: its details and chapters when opened from the novel
 * screen, [chapterId]'s text when opened from the reader, and only what the novel's source takes.
 */
@Composable
fun rememberNovelPageActions(
    novelId: Long?,
    chapterId: Long? = null,
    onChapterSaved: () -> Unit = {},
): List<WebPageAction> {
    val context = LocalContext.current
    val fetcher = remember { context.appGraph.novelPageFetcher }
    val scope = rememberCoroutineScope()
    val kinds by produceState(emptySet<NovelPageKind>(), novelId) {
        value = novelId?.let { fetcher.kinds(it) }.orEmpty()
    }
    val forDetails = stringResource(MR.strings.novel_page_use_for_details)
    val forChapters = stringResource(MR.strings.novel_page_use_for_chapters)
    val forChapter = stringResource(MR.strings.novel_page_use_for_chapter)
    if (novelId == null) return emptyList()

    fun run(block: suspend () -> String) = scope.launch { context.toast(block()) }
    val unreadable = context.stringResource(MR.strings.novel_page_unreadable)

    return buildList {
        if (chapterId == null && NovelPageKind.DETAILS in kinds) {
            add(
                WebPageAction(forDetails) { url, html ->
                    run {
                        val ok = fetcher.useForDetails(novelId, url, html)
                        if (ok) context.stringResource(MR.strings.novel_page_details_updated) else unreadable
                    }
                },
            )
        }
        if (chapterId == null && NovelPageKind.CHAPTERS in kinds) {
            add(
                WebPageAction(forChapters) { url, html ->
                    run {
                        fetcher.useForChapters(novelId, url, html)
                            ?.let { context.stringResource(MR.strings.novel_page_chapters_found, it) }
                            ?: unreadable
                    }
                },
            )
        }
        if (chapterId != null && NovelPageKind.CHAPTER_TEXT in kinds) {
            add(
                WebPageAction(forChapter) { url, html ->
                    run {
                        if (fetcher.useForChapterText(chapterId, url, html)) {
                            onChapterSaved()
                            context.stringResource(MR.strings.novel_page_chapter_saved)
                        } else {
                            unreadable
                        }
                    }
                },
            )
        }
    }
}
