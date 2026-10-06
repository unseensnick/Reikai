package reikai.presentation.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toShareIntent
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import reikai.domain.entry.EntryId
import reikai.domain.novel.model.Novel
import reikai.domain.source.SourceKey
import reikai.domain.source.mangaUrlOrNull
import reikai.novel.source.NovelSource
import reikai.presentation.webview.toWebViewScreen
import tachiyomi.domain.manga.model.Manga

/**
 * The web page of the member the details screen shows (the selected chip's, else the anchor's), which the
 * assistant link, WebView, Share and Copy link all open. Null hides those actions, so none is a tap that
 * does nothing.
 */
@Immutable
data class EntryWebPage(val url: String, val source: SourceKey, val viewed: EntryId) {
    companion object {
        fun of(url: String?, source: SourceKey, viewed: EntryId): EntryWebPage? =
            url?.takeIf { it.isNotBlank() }?.let { EntryWebPage(it, source, viewed) }
    }
}

/** Upstream's rule: only an HTTP source has a page, and one whose address rule throws has none. */
fun Manga.webPageIn(source: Source): EntryWebPage? {
    val http = source as? HttpSource ?: return null
    return EntryWebPage.of(http.mangaUrlOrNull(toSManga()), SourceKey.Manga(source.id), EntryId.Manga(id))
}

suspend fun Novel.webPageIn(source: NovelSource): EntryWebPage? =
    EntryWebPage.of(source.webUrl(url, isNovel = true), SourceKey.Novel(source.id), EntryId.Novel(id))

/** The shown manga member's page, asked of its extension only when that member changes, not per download. */
internal fun Flow<MangaViewModel.State>.shownWebPages(): Flow<EntryWebPage?> =
    mapNotNull { state -> (state as? MangaViewModel.State.Success)?.let { it.shownManga to it.shownSource } }
        .distinctUntilChanged()
        .map { (manga, source) -> manga.webPageIn(source) }

/** The details body's actions on [EntryWebPage], plus the tag copy, its other clipboard write. */
internal class EntryWebActions(
    val openWebView: (() -> Unit)?,
    val share: (() -> Unit)?,
    val copyUrl: (() -> Unit)?,
    val copyTag: (String) -> Unit,
)

@Composable
internal fun rememberEntryWebActions(page: EntryWebPage?, title: String): EntryWebActions {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    return remember(page, title, context, navigator) {
        EntryWebActions(
            openWebView = page?.let {
                { navigator.push(it.source.toWebViewScreen(it.url, title, (it.viewed as? EntryId.Novel)?.rawId)) }
            },
            share = page?.let {
                {
                    try {
                        context.startActivity(it.url.toUri().toShareIntent(context, type = "text/plain"))
                    } catch (e: Exception) {
                        context.toast(e.message)
                    }
                }
            },
            copyUrl = page?.let { { context.copyToClipboard(it.url, it.url) } },
            copyTag = { if (it.isNotEmpty()) context.copyToClipboard(it, it) },
        )
    }
}
