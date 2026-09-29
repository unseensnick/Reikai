package reikai.presentation.browse.globalsearch

import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import reikai.domain.library.ContentType

/**
 * The screen an extension's search intent opens. A novel app's link handler sends its link as the query,
 * as keiyoushi's `UrlActivity` does, so it is read like a shared link; the app answers through its links
 * capability. Any other filter names a manga extension, and a bare intent opens on the Browse chip.
 */
suspend fun searchIntentScreen(
    query: String,
    filter: String?,
    novelPackages: suspend () -> Collection<String>,
): Screen =
    when {
        filter.isNullOrEmpty() -> EntryGlobalSearchScreen(query)
        filter in novelPackages() -> DeepLinkScreen(query)
        else -> EntryGlobalSearchScreen(query, filter, scopedContentType = ContentType.MANGA)
    }
