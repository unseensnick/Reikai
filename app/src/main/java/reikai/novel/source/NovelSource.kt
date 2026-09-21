package reikai.novel.source

import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel

/** The id prefix of a novel source packaged as a tachiyomi-format APK, whose own id is a number. */
const val TACHIYOMI_NOVEL_SOURCE_PREFIX = "tachiyomi:"

/**
 * Contract for a light-novel source, whatever format it comes in. Everything is suspending and
 * content-shaped (`NovelItem`, `SourceNovel`, chapter text as `String`) rather than the `SManga` /
 * `SChapter` / `Page` the manga side uses. Where the formats differ, the difference is a typed
 * capability such as [filters], never a check for which format a source is.
 *
 * [LnPluginSource] is the LNReader plugin adapter (a JS plugin running in an [reikai.novel.host.LnPluginHost]).
 */
interface NovelSource {

    /** Source id, matches the upstream lnreader registry's `id` field (e.g. `novelbin`). */
    val id: String

    val name: String
    val version: String
    val site: String

    /** lnreader plugin language tag (e.g. `en`, `id`, `zh`). Empty when the plugin doesn't declare
     *  one. Drives the Language section grouping on the Browse sources list. */
    val lang: String

    /**
     * Absolute CDN URL for the source's icon, resolved at install time from the lnreader registry's
     * `iconUrl` field. Null for installs whose lazy backfill hasn't matched a repo yet, or for
     * direct-URL-paste installs that bypass the registry.
     */
    val iconUrl: String?

    /** The source's filters and where they apply; null when it declares none. */
    val filters: NovelFilters? get() = null

    /** The source's own settings, in the shape its format declares; null when it has none. */
    val settings: NovelSettings? get() = null

    /**
     * This source can serve a Latest listing, which is why browse offers the chip. The lnreader
     * format declares no such flag, so it is derived rather than read, by looking for
     * `showLatestNovels` in the plugin's own source text. A plugin that never mentions it would
     * answer a Latest request with the Popular list, so browse hides the chip instead.
     */
    val supportsLatest: Boolean get() = false

    /**
     * A page of [listing]. Null [filters] means the source's defaults; a format whose filters apply to
     * search ignores them here, as a manga source's Popular and Latest take none.
     */
    suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): List<NovelItem>

    /**
     * A page of search results. Null [filters] means the source's defaults; a format whose search takes
     * no filters ignores them.
     */
    suspend fun search(query: String, page: Int, filters: NovelFilterState?): List<NovelItem>

    /** Fetch a novel's details + chapter list. `novelPath` is the source-relative path returned
     *  inside a [NovelItem]. */
    suspend fun parseNovel(novelPath: String): SourceNovel

    /**
     * Fetch the chapter list for a single page of a paged-novel source (Royal Road volumes, etc.).
     * Plugins whose chapter lists span multiple endpoints override this; the default returns null,
     * meaning "this source is single-page, just call [parseNovel]". Mirrors lnreader's
     * `Plugin.parsePage`.
     */
    suspend fun parsePage(novelPath: String, page: String): SourceNovel? = null

    /** Fetch chapter HTML/text body. `chapterPath` is the source-relative path returned inside a
     *  [SourceNovel.chapters] entry. */
    suspend fun parseChapter(chapterPath: String): String

    /**
     * Resolve a source-relative [path] to its absolute web URL via the plugin's optional lnreader
     * `resolveUrl`. Returns null when the plugin doesn't implement it (only some do), so callers
     * fall back to [site] (the source homepage).
     */
    suspend fun resolveUrl(path: String, isNovel: Boolean): String? = null

    /**
     * Full browser URL for a source-relative [path] (a novel or chapter), for opening in WebView or
     * sharing: the path itself if it is already absolute, else [site] + path. Mirrors lnreader's
     * `resolveUrl` service fallback (`plugin.site + path`); the [resolveUrl] plugin override is left
     * for a future caller since almost no plugins implement it.
     */
    fun webUrl(path: String): String = if (path.startsWith("http")) path else site + path
}
