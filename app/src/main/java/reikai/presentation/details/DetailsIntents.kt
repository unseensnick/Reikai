package reikai.presentation.details

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.ui.main.MainActivity
import reikai.domain.novel.NovelRepository
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.core.common.Constants
import tachiyomi.core.common.util.lang.withIOContext

// The intents that open a series' details through MainActivity. Each sets only the route; callers add
// their own flags, categories and notification extras.

fun mangaDetailsIntent(context: Context, mangaId: Long): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Constants.SHORTCUT_MANGA)
        .putExtra(Constants.MANGA_EXTRA, mangaId)

// By source and url rather than row id, since that is what the novel screen is pushed with.
fun novelDetailsIntent(context: Context, source: String, url: String): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(Constants.SHORTCUT_NOVEL)
        .putExtra(Constants.NOVEL_SOURCE_EXTRA, source)
        .putExtra(Constants.NOVEL_URL_EXTRA, url)

/**
 * The screen a [novelDetailsIntent] opens, or null when either extra is missing or names a novel this
 * device has not saved. MainActivity is exported, so any app can send one, and a novel page opened on an
 * unsaved url fetches it from the source and saves it; Mihon's manga shortcut carries a row id for the same
 * reason.
 */
suspend fun Intent.novelDetailsScreen(novels: NovelRepository): NovelScreen? {
    val source = getStringExtra(Constants.NOVEL_SOURCE_EXTRA) ?: return null
    val url = getStringExtra(Constants.NOVEL_URL_EXTRA) ?: return null
    withIOContext { novels.getByUrlAndSource(url, source) } ?: return null
    return NovelScreen(source, url)
}
