package reikai.presentation.browse.globalsearch

import androidx.navigation3.runtime.NavKey
import mihon.core.navigation.DeepLinkRoute
import reikai.domain.library.ContentType

/**
 * The screen an extension's search intent opens. A novel app's link handler sends its link as the query,
 * as keiyoushi's `UrlActivity` does, so it is read like a shared link; the app answers through its links
 * capability. Any other filter names a manga extension, and a bare intent searches as [outsideSearchRoute].
 */
suspend fun searchIntentRoute(
    query: String,
    filter: String?,
    novelPackages: suspend () -> Collection<String>,
): NavKey =
    when {
        filter.isNullOrEmpty() -> outsideSearchRoute(query)
        filter in novelPackages() -> DeepLinkRoute(query)
        else -> EntryGlobalSearchRoute(query, filter, scopedContentType = ContentType.MANGA)
    }

/**
 * A search that arrives from outside the app, a shared link that matched nothing or another app's search
 * request, covers every content type and every source: the query may be either type and from any site, so
 * the chip and source filter you last left in Browse would hide results. Neither choice is remembered.
 */
fun outsideSearchRoute(query: String): NavKey =
    EntryGlobalSearchRoute(query, scopedContentType = ContentType.ALL, sourceFilter = SearchSourceFilter.All)
