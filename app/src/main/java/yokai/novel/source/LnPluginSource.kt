package yokai.novel.source

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import yokai.novel.host.LnPluginHost
import yokai.novel.host.LnPluginInfo
import yokai.novel.host.NovelItem
import yokai.novel.host.SourceNovel

/**
 * [NovelSource] backed by an upstream lnreader plugin running inside an [LnPluginHost]. The
 * adapter does not own the host: construct one for every plugin you load on the host, and dispose
 * them together when the host goes away.
 *
 * The plugin id stored in [info] is the same string passed to `host.loadPlugin(pluginId, source)`,
 * so the adapter can route every method call to the right plugin instance inside the WebView.
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
    override val filters: JsonObject? = info.filters
    override val pluginSettings: JsonObject? = info.pluginSettings

    override suspend fun getSetting(key: String): JsonElement? = host.getSetting(info.id, key)

    override fun setSetting(key: String, value: JsonElement?) = host.setSetting(info.id, key, value)

    override suspend fun popularNovels(page: Int, optionsJson: String): List<NovelItem> =
        host.popularNovels(info.id, page, optionsJson)

    override suspend fun searchNovels(query: String, page: Int): List<NovelItem> =
        host.searchNovels(info.id, query, page)

    override suspend fun parseNovel(novelPath: String): SourceNovel =
        host.parseNovel(info.id, novelPath)

    override suspend fun parseChapter(chapterPath: String): String =
        host.parseChapter(info.id, chapterPath)

    override suspend fun resolveUrl(path: String, isNovel: Boolean): String? =
        host.resolveUrl(info.id, path, isNovel)
}
