package reikai.data.coil

import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Call
import okhttp3.Headers

/**
 * The client a cover is fetched with and the headers sent with it, which each content type resolves
 * from its own source, so manga and novel covers share `MangaCoverFetcher`'s caching.
 */
class CoverRequestClient(val callFactory: Call.Factory, val headers: Headers?)

/** A manga source's own client and headers, or the app's client when it is not an HTTP source. */
fun Source?.coverRequestClient(appClient: Lazy<Call.Factory>): CoverRequestClient {
    val source = this as? HttpSource
    return CoverRequestClient(source?.client ?: appClient.value, source?.headers)
}
