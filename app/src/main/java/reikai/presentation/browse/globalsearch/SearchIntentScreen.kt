package reikai.presentation.browse.globalsearch

import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import reikai.domain.library.ContentType

/**
 * The screen an extension's search intent opens. A novel app's link handler sends its link as the query,
 * as keiyoushi's `UrlActivity` does, so it is read like a shared link; the app answers through its links
 * capability. Any other filter names a manga extension, and a bare intent searches as [outsideSearchScreen].
 */
suspend fun searchIntentScreen(
    query: String,
    filter: String?,
    novelPackages: suspend () -> Collection<String>,
): Screen =
    when {
        filter.isNullOrEmpty() -> outsideSearchScreen(query)
        filter in novelPackages() -> DeepLinkScreen(query)
        else -> EntryGlobalSearchScreen(query, filter, scopedContentType = ContentType.MANGA)
    }

/**
 * A search that arrives from outside the app, a shared link that matched nothing or another app's search
 * request, covers every content type and every source: the query may be either type and from any site, so
 * the chip and source filter you last left in Browse would hide results. Neither choice is remembered.
 */
fun outsideSearchScreen(query: String): Screen =
    EntryGlobalSearchScreen(query, scopedContentType = ContentType.ALL, sourceFilter = SearchSourceFilter.All)
