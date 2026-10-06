package reikai.domain.source

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import reikai.novel.source.ireader.IReaderSourceHolder

/**
 * The site an installed app's source opens on, which the Extensions search matches as Mihon's does:
 * an HTTP source's home URL, or an IReader catalogue's base URL. Null for a source with no site.
 */
val Source.homeUrl: String?
    get() = when (this) {
        is HttpSource -> getHomeUrl()
        is IReaderSourceHolder -> baseUrl
        else -> null
    }

/** Every address an installed app's source serves, which Clear cookies clears as Mihon's does: its base and home URLs. */
val Source.siteUrls: List<String>
    get() = listOfNotNull((this as? HttpSource)?.baseUrl, homeUrl)
