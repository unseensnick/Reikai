package reikai.presentation.webview

import android.content.Context
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import reikai.novel.source.NovelPageFetcher
import reikai.novel.source.NovelPageFetcher.ChapterFromPage
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR

/**
 * Runs the in-app browser's novel page actions on an app-wide scope and reports each with a toast, so
 * a save started in the browser finishes, and says so, after the browser is closed.
 */
@Inject
@SingleIn(AppScope::class)
class NovelPageActionRunner(
    private val context: Context,
    private val fetcher: NovelPageFetcher,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun useForDetails(novelId: Long, url: String, html: String) = run {
        if (fetcher.useForDetails(novelId, url, html)) string(MR.strings.novel_page_details_updated) else unreadable()
    }

    fun useForChapters(novelId: Long, url: String, html: String) = run {
        when (val found = fetcher.useForChapters(novelId, url, html)) {
            null -> unreadable()
            0 -> string(MR.strings.novel_page_no_chapters)
            else -> context.pluralStringResource(MR.plurals.novel_page_chapters_found, found, found)
        }
    }

    fun useForChapterText(chapterId: Long, url: String, html: String) = run {
        when (fetcher.useForChapterText(chapterId, url, html)) {
            ChapterFromPage.SAVED -> string(MR.strings.novel_page_chapter_saved)
            ChapterFromPage.NO_TEXT -> string(MR.strings.novel_page_no_chapter_text)
            ChapterFromPage.UNREADABLE -> unreadable()
        }
    }

    private fun run(block: suspend () -> String) {
        scope.launch {
            val message = block()
            withUIContext { context.toast(message) }
        }
    }

    private fun string(resource: StringResource) = context.stringResource(resource)

    private fun unreadable() = string(MR.strings.novel_page_unreadable)
}
