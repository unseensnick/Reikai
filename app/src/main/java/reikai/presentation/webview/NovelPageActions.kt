package reikai.presentation.webview

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.util.system.toast
import mihon.app.di.appGraph
import reikai.novel.source.NovelPageFetcher.ChapterFromPage
import reikai.novel.source.NovelPageKind
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withUIContext
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

    val app = context.applicationContext
    fun run(block: suspend () -> String) = scope.launchNonCancellable { withUIContext { app.toast(block()) } }
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
                        when (val found = fetcher.useForChapters(novelId, url, html)) {
                            null -> unreadable
                            0 -> context.stringResource(MR.strings.novel_page_no_chapters)
                            else -> context.pluralStringResource(MR.plurals.novel_page_chapters_found, found, found)
                        }
                    }
                },
            )
        }
        if (chapterId != null && NovelPageKind.CHAPTER_TEXT in kinds) {
            add(
                WebPageAction(forChapter) { url, html ->
                    run {
                        when (fetcher.useForChapterText(chapterId, url, html)) {
                            ChapterFromPage.SAVED -> context.stringResource(MR.strings.novel_page_chapter_saved)
                            ChapterFromPage.NO_TEXT -> context.stringResource(MR.strings.novel_page_no_chapter_text)
                            ChapterFromPage.UNREADABLE -> unreadable
                        }
                    }
                },
            )
        }
    }
}
