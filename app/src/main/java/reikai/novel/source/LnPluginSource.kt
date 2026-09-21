package reikai.novel.source

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginInfo
import reikai.novel.host.NovelItem
import reikai.novel.host.NovelTextSanitizer
import reikai.novel.host.SourceNovel

/**
 * [NovelSource] backed by an upstream lnreader plugin running inside an [LnPluginHost]. The adapter
 * does not own the host: construct one for every plugin loaded on the host, and dispose them
 * together when the host goes away.
 *
 * The plugin id stored in [info] is the same string passed to `host.loadPlugin(pluginId, source)`,
 * so the adapter routes every method call to the right plugin instance inside the engine.
 */
class LnPluginSource(
    private val host: LnPluginHost,
    private val info: LnPluginInfo,
) : NovelSource {

    override val id: String = info.id
    override val name: String = info.name
    override val version: String = info.version.orEmpty()
    override val site: String = info.site.orEmpty()
    override val lang: String = info.lang.orEmpty()
    override val iconUrl: String? = info.iconUrl
    override val filters: NovelFilters? = info.filters?.takeIf { it.isNotEmpty() }?.let(NovelFilters::LnSchema)
    override val pluginSettings: JsonObject? = info.pluginSettings
    override val supportsLatest: Boolean = info.supportsLatest

    override suspend fun getSetting(key: String): JsonElement? = host.getSetting(info.id, key)

    override fun setSetting(key: String, value: JsonElement?) = host.setSetting(info.id, key, value)

    // Latest is a flag in the same options as the filters, and a plugin with no filters still needs it.
    override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): List<NovelItem> {
        val values = (filters as? NovelFilterState.LnValues)?.values.orEmpty()
        val options = buildOptions(info.filters, values, showLatest = listing == NovelListing.Latest)
        return host.popularNovels(info.id, page, options)
    }

    // The plugin's search takes only a query, so filters narrow the listings and never reach here.
    override suspend fun search(query: String, page: Int, filters: NovelFilterState?): List<NovelItem> =
        host.searchNovels(info.id, query, page)

    override suspend fun parseNovel(novelPath: String): SourceNovel =
        host.parseNovel(info.id, novelPath)

    override suspend fun parsePage(novelPath: String, page: String): SourceNovel? =
        host.parsePage(info.id, novelPath, page)

    // Strip control chars only (never entities): the body is HTML the reader renders, so its markup
    // and escaped entities must survive intact.
    override suspend fun parseChapter(chapterPath: String): String =
        NovelTextSanitizer.stripInvalidChars(host.parseChapter(info.id, chapterPath))

    override suspend fun resolveUrl(path: String, isNovel: Boolean): String? =
        host.resolveUrl(info.id, path, isNovel)
}
