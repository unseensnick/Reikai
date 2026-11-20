package reikai.presentation.details

import android.content.Context
import android.content.Intent
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.util.MainActivityTarget
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withIOContext

// The intents that open a series' details through MainActivity. Each sets only the route; callers add
// their own flags, categories and notification extras.

fun mangaDetailsIntent(context: Context, mangaId: Long): Intent =
    Intent(context, MainActivityTarget::class.java)
        .setAction(Constants.SHORTCUT_MANGA)
        .putExtra(Constants.MANGA_EXTRA, mangaId)

fun novelDetailsIntent(context: Context, novelId: Long): Intent =
    Intent(context, MainActivityTarget::class.java)
        .setAction(Constants.SHORTCUT_NOVEL)
        .putExtra(Constants.NOVEL_EXTRA, novelId)

/** The novel a [novelDetailsIntent] opens, or null when it names none, as manga's intent names its id. */
fun Intent.novelToOpen(): EntryId.Novel? =
    getLongExtra(Constants.NOVEL_EXTRA, -1L).takeIf { it > 0L }?.let(EntryId::Novel)

/**
 * The novel an intent an older build wrote (a pinned widget, a posted notification) opens, by the source and
 * url it carries, read back only to a novel this device has saved: MainActivity is exported, so any app can
 * send one, and no url it sends is ever fetched unless a row already holds it.
 */
suspend fun Intent.legacyNovelToOpen(novels: NovelRepository): EntryId.Novel? {
    val source = getStringExtra(Constants.NOVEL_SOURCE_EXTRA) ?: return null
    val url = getStringExtra(Constants.NOVEL_URL_EXTRA) ?: return null
    val stored = withIOContext { novels.getByUrlAndSource(url, source) } ?: return null
    return EntryId.Novel(stored.id)
}
