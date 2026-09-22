package reikai.novel.network

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.first
import okhttp3.Headers
import okhttp3.OkHttpClient
import reikai.domain.novel.NovelPreferences
import reikai.novel.source.IREADER_NOVEL_SOURCE_PREFIX
import reikai.novel.source.TACHIYOMI_NOVEL_SOURCE_PREFIX
import reikai.novel.source.ireader.IReaderSourceHolder
import ireader.core.source.HttpSource as IReaderHttpSource

/** The client and headers a novel source's images are fetched with. */
class NovelImageClient(val client: OkHttpClient, val headers: Headers)

/**
 * How each novel source's images are fetched, for covers, both reader modes and downloads alike.
 * Answered without running any plugin: a cover can be drawn before one has loaded, so an LNReader
 * source is read from the record its last load saved, and an APK source from its loaded extension.
 */
@Inject
@SingleIn(AppScope::class)
class NovelImageRequests(
    private val context: Context,
    private val network: NetworkHelper,
    private val novelPreferences: NovelPreferences,
    private val extensionManager: ExtensionManager,
) {

    suspend fun forSource(sourceId: String?): NovelImageClient {
        apkSource(sourceId)?.let { return NovelImageClient(it.client, it.headers) }
        iReaderSource(sourceId)?.let { return NovelImageClient(network.client, iReaderImageHeaders(it)) }
        val record = sourceId?.let { novelPreferences.seenNovelSources().get()[it] }
        return NovelImageClient(
            network.client,
            lnImageHeaders(deviceWebViewUserAgent(context), record?.site, record?.imageHeaders.orEmpty()),
        )
    }

    // Found by its whole text id: an IReader app and a tachiyomi-format one may share the number.
    private suspend fun iReaderSource(sourceId: String?): IReaderSourceHolder? {
        sourceId ?: return null
        return extensionManager.loadedNovelExtensionsFlow.first()
            .flatMap { it.sources.filterIsInstance<IReaderSourceHolder>() }
            .firstOrNull { IREADER_NOVEL_SOURCE_PREFIX + it.id == sourceId }
    }

    private suspend fun apkSource(sourceId: String?): HttpSource? {
        val id = sourceId?.removePrefix(TACHIYOMI_NOVEL_SOURCE_PREFIX)
            ?.takeIf { it != sourceId }
            ?.toLongOrNull()
            ?: return null
        return extensionManager.loadedNovelExtensionsFlow.first()
            .firstNotNullOfOrNull { extension -> extension.sources.firstOrNull { it.id == id } as? HttpSource }
    }
}

/**
 * An LNReader source's image headers: the device user agent, an image Accept, and the site as Referer,
 * which some hosts refuse an image without, then the plugin's own, which win (`imageRequestInit`).
 */
internal fun lnImageHeaders(deviceUserAgent: String, site: String?, pluginHeaders: Map<String, String>): Headers =
    Headers.Builder().apply {
        if (deviceUserAgent.isNotBlank()) set("User-Agent", deviceUserAgent)
        set("Accept", "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8")
        if (!site.isNullOrBlank()) set("Referer", site)
        // A name or value no header can carry throws, and drops only that header.
        pluginHeaders.forEach { (name, value) -> runCatching { set(name, value) } }
    }.build()

/**
 * An IReader source's picture headers, from the cover request it builds: that is where its extensions
 * set a Referer or user agent, and none overrides the page-image request. The site stands in for the
 * cover, since the headers do not depend on which picture is asked for.
 */
internal fun iReaderImageHeaders(holder: IReaderSourceHolder): Headers {
    val source = holder.source as? IReaderHttpSource ?: return Headers.headersOf()
    val requested = runCatching { source.getCoverRequest(source.baseUrl).second.headers.build() }.getOrNull()
        ?: return Headers.headersOf()
    return Headers.Builder().apply {
        requested.forEach { name, values -> values.forEach { runCatching { add(name, it) } } }
    }.build()
}
