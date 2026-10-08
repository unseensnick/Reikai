package reikai.novel.network

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import logcat.LogPriority
import okhttp3.CacheControl
import okhttp3.Call
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.siteHost
import reikai.novel.source.AppNovelSource
import reikai.novel.source.NovelSourceManager
import reikai.novel.source.isNovelAppSourceId
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.system.logcat
import ireader.core.source.HttpSource as IReaderHttpSource

/** The client and headers a novel source's images are fetched with. */
class NovelImageClient(
    val client: OkHttpClient,
    val headers: Headers,
    private val site: String? = null,
    private val elsewhere: NovelImageClient? = null,
) {
    /**
     * The client for a chapter picture at [url]. Whoever wrote the chapter names its pictures, so only
     * one on the source's own site gets the source's client and headers, which can carry a login. Any
     * other host gets [elsewhere]: the app's client, the source's user agent and its site as Referer.
     */
    fun forUrl(url: String): NovelImageClient = if (elsewhere == null || isSameSite(url, site)) this else elsewhere

    /**
     * The call for the picture at [url], for every novel picture fetch. It skips OkHttp's own cache,
     * which keeps a failed answer that a retry, or downloading the chapter again, then reads back.
     */
    fun newCall(url: String): Call {
        val picture = forUrl(url)
        val request = Request.Builder().url(url).headers(picture.headers).cacheControl(NO_STORE).build()
        return picture.client.newCall(request)
    }
}

private val NO_STORE = CacheControl.Builder().noStore().build()

/**
 * Whether [url] is on [site]'s host or a subdomain of it, `www.` aside, so a site's own CDN counts.
 * Deliberately narrower than a registrable-domain match, which needs a public-suffix list: a sibling
 * subdomain still gets the plain client, which only drops what could carry a login.
 */
internal fun isSameSite(url: String, site: String?): Boolean {
    val picture = url.toHttpUrlOrNull()?.host ?: return false
    val own = siteHost(site) ?: return false
    return picture == own || picture.endsWith(".$own")
}

/**
 * How each novel source's images are fetched, for covers, both reader modes and downloads alike.
 * Answered without running any plugin: a cover can be drawn before one has loaded, so an LNReader
 * source is read from the record its last load saved, and an app's source from its registered catalogue.
 */
@Inject
@SingleIn(AppScope::class)
class NovelImageRequests(
    private val context: Context,
    private val network: NetworkHelper,
    private val novelPreferences: NovelPreferences,
    private val sourceManager: NovelSourceManager,
) {

    suspend fun forSource(sourceId: String?): NovelImageClient {
        appImages(sourceId)?.let { return it }
        val record = sourceId?.let { novelPreferences.seenNovelSources().get()[it] }
        return withElsewhere(
            network.client,
            lnImageHeaders(deviceWebViewUserAgent(context), record?.site, record?.imageHeaders.orEmpty()),
            record?.site,
        )
    }

    /**
     * The headers a novel source sends with its own pages, for the WebView: an APK source's own and an
     * IReader source's cover-request ones. An LNReader plugin has none of its own, so it gets none, and
     * so does a source whose headers throw, as a manga source's do in the WebView.
     */
    suspend fun webViewHeaders(sourceId: String?): Map<String, String> =
        runCatchingCancellable {
            appImages(sourceId)?.headers?.toMultimap()?.mapValues { it.value.firstOrNull().orEmpty() }.orEmpty()
        }
            .onFailure { logcat(LogPriority.ERROR, it) { "Failed to build headers" } }
            .getOrDefault(emptyMap())

    // An app's id is checked first, since the registry answers only once the extension scan is done.
    private suspend fun appImages(sourceId: String?): NovelImageClient? {
        if (sourceId == null || !isNovelAppSourceId(sourceId)) return null
        return when (val catalogue = (sourceManager.getWithoutPlugins(sourceId) as? AppNovelSource)?.appSource) {
            is HttpSource -> withElsewhere(catalogue.client, catalogue.headers, catalogue.baseUrl)
            is IReaderHttpSource -> withElsewhere(network.client, iReaderImageHeaders(catalogue), catalogue.baseUrl)
            else -> null
        }
    }

    private fun withElsewhere(client: OkHttpClient, headers: Headers, site: String?): NovelImageClient {
        val plain = lnImageHeaders(headers["User-Agent"].orEmpty(), site, emptyMap())
        return NovelImageClient(client, headers, site, NovelImageClient(network.client, plain))
    }
}

private const val IMAGE_ACCEPT = "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8"

/**
 * An LNReader source's image headers: the device user agent, an image Accept, and the site as Referer,
 * which some hosts refuse an image without, then the plugin's own, which win (`imageRequestInit`).
 */
internal fun lnImageHeaders(deviceUserAgent: String, site: String?, pluginHeaders: Map<String, String>): Headers =
    Headers.Builder().apply {
        if (deviceUserAgent.isNotBlank()) set("User-Agent", deviceUserAgent)
        set("Accept", IMAGE_ACCEPT)
        if (!site.isNullOrBlank()) set("Referer", site)
        // A name or value no header can carry throws, and drops only that header.
        pluginHeaders.forEach { (name, value) -> runCatching { set(name, value) } }
    }.build()

/**
 * An IReader source's picture headers, from the cover request it builds: that is where its extensions
 * set a Referer or user agent, and none overrides the page-image request. The site stands in for the
 * cover, since the headers do not depend on which picture is asked for.
 */
internal fun iReaderImageHeaders(source: IReaderHttpSource): Headers {
    val requested = runCatching { source.getCoverRequest(source.baseUrl).second.headers.build() }.getOrNull()
        ?: return Headers.headersOf()
    return Headers.Builder().apply {
        requested.forEach { name, values -> values.forEach { runCatching { add(name, it) } } }
    }.build()
}
