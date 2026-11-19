package reikai.presentation.details

import android.content.Context
import android.content.Intent
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.main.MainActivity
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withIOContext

// The intents that open a series' details through MainActivity. Each sets only the route; callers add
// their own flags, categories and notification extras.

fun mangaDetailsIntent(context: Context, mangaId: Long): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Constants.SHORTCUT_MANGA)
        .putExtra(Constants.MANGA_EXTRA, mangaId)

fun novelDetailsIntent(context: Context, novelId: Long): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Constants.SHORTCUT_NOVEL)
        .putExtra(Constants.NOVEL_EXTRA, novelId)

/**
 * The screen a [novelDetailsIntent] opens, or null when it names no novel. An intent an older build
 * wrote (a pinned widget, a posted notification) carries the novel's source and url instead, read back
 * only to a novel this device has saved: MainActivity is exported, so any app can send one, and nothing
 * it sends may fetch from a source.
 */
suspend fun Intent.novelDetailsScreen(novels: NovelRepository): Screen? {
    getLongExtra(Constants.NOVEL_EXTRA, -1L).takeIf { it > 0L }?.let { return EntryId.Novel(it).detailsScreen() }
    val source = getStringExtra(Constants.NOVEL_SOURCE_EXTRA) ?: return null
    val url = getStringExtra(Constants.NOVEL_URL_EXTRA) ?: return null
    val stored = withIOContext { novels.getByUrlAndSource(url, source) } ?: return null
    return EntryId.Novel(stored.id).detailsScreen()
}
