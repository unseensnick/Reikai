package reikai.presentation.details

import android.content.Context
import android.content.Intent
import eu.kanade.tachiyomi.ui.main.MainActivity
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.core.common.Constants

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

/** The screen a [novelDetailsIntent] opens, or null when either extra is missing. */
fun Intent.novelDetailsScreen(): NovelScreen? {
    val source = getStringExtra(Constants.NOVEL_SOURCE_EXTRA) ?: return null
    val url = getStringExtra(Constants.NOVEL_URL_EXTRA) ?: return null
    return NovelScreen(source, url)
}
