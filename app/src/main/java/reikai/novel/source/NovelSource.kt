package reikai.novel.source

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.SourceTracker
import kotlinx.coroutines.CoroutineScope
import mihon.domain.extension.model.ContentWarning
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel
import tachiyomi.core.common.util.lang.withIOContext

/** The id prefix of a novel source packaged as a tachiyomi-format APK, whose own id is a number. */
const val TACHIYOMI_NOVEL_SOURCE_PREFIX = "tachiyomi:"

/** The id prefix of a novel source packaged as an IReader APK, whose own id is a number. */
const val IREADER_NOVEL_SOURCE_PREFIX = "ireader:"

/**
 * The text id of the catalogue numbered [sourceId] in an app of this kind, or null for a manga app,
 * whose sources keep their numbers. The prefix is what keeps two formats' equal numbers apart.
 */
fun Extension.Kind.novelSourceId(sourceId: Long): String? = when (this) {
    Extension.Kind.MANGA -> null
    Extension.Kind.TACHIYOMI_NOVEL -> TACHIYOMI_NOVEL_SOURCE_PREFIX + sourceId
    Extension.Kind.IREADER -> IREADER_NOVEL_SOURCE_PREFIX + sourceId
}

/** Whether [id] names a catalogue of an installed app, rather than an LNReader plugin. */
fun isNovelAppSourceId(id: String): Boolean =
    id.startsWith(TACHIYOMI_NOVEL_SOURCE_PREFIX) || id.startsWith(IREADER_NOVEL_SOURCE_PREFIX)

/** How an app of this kind is packaged, or null for a manga app. */
val Extension.Kind.novelFormat: NovelExtensionFormat?
    get() = when (this) {
        Extension.Kind.MANGA -> null
        Extension.Kind.TACHIYOMI_NOVEL -> NovelExtensionFormat.APK
        Extension.Kind.IREADER -> NovelExtensionFormat.IREADER
    }

/**
 * Contract for a light-novel source, whatever format it comes in. Everything is suspending and
 * content-shaped (`NovelItem`, `SourceNovel`, chapter text as `String`) rather than the `SManga` /
 * `SChapter` / `Page` the manga side uses. Where the formats differ, the difference is a typed
 * capability such as [filters], never a check for which format a source is.
 *
 * [LnPluginSource] is the LNReader plugin adapter (a JS plugin running in an [reikai.novel.host.LnPluginHost]).
 */
interface NovelSource {

    /** Source id: a plugin's registry id (`novelbin`), or an app source's prefixed one (`tachiyomi:N`, `ireader:N`). */
    val id: String

    val name: String
    val version: String
    val site: String

    /** lnreader plugin language tag (e.g. `en`, `id`, `zh`). Empty when the plugin doesn't declare
     *  one. Drives the Language section grouping on the Browse sources list. */
    val lang: String

    /**
     * The source's icon: a plugin's from its registry entry (null until a repo has been matched, or for a
     * pasted install), an app's from the app itself.
     */
    val iconUrl: String?

    /** How the source is packaged, which lists name once more than one kind is shown. */
    val format: NovelExtensionFormat

    /** What installed the source: an app's own name, or the plugin's, since a plugin is its own extension. */
    val extensionName: String

    /** The installing app's content warning. A plugin answers SAFE, since its format has no adult flag. */
    val contentWarning: ContentWarning

    /** The source's filters and where they apply; null when it declares none. */
    val filters: NovelFilters? get() = null

    /** The source's own settings, in the shape its format declares; null when it has none. */
    val settings: NovelSettings? get() = null

    /** The hooks through which the source syncs reading to its own site; null when it has none. */
    val tracker: SourceTracker? get() = null

    /** The least delay the source asks for between requests to its site; 0 when it asks for none. */
    val minimumRequestDelayMs: Long get() = 0L

    /** Reads a page the user loaded in the in-app browser; null when the source cannot be handed one. */
    val pageFetch: NovelPageFetch? get() = null

    /** The stylesheet the source ships for its chapters; null when it ships none. */
    val chapterStylesheet: NovelChapterStylesheet? get() = null

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
    suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage

    /**
     * A page of search results. Null [filters] means the source's defaults; a format whose search takes
     * no filters ignores them.
     */
    suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage

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
     * The source's own rule for a source-relative [path]'s absolute web URL, or null when it has none: a
     * plugin's optional lnreader `resolveUrl`, an app's own address rule.
     */
    suspend fun resolveUrl(path: String, isNovel: Boolean): String? = null

    /**
     * Full browser URL for a source-relative [path], a novel's when [isNovel] and a chapter's otherwise,
     * for opening in WebView or sharing. As lnreader's service does: the source's own [resolveUrl] first,
     * else the path itself if it is already absolute, else [site] + path.
     */
    suspend fun webUrl(path: String, isNovel: Boolean): String =
        resolveUrl(path, isNovel) ?: if (path.startsWith("http")) path else site + path
}

/**
 * A novel source that is a catalogue of an installed app rather than a plugin. [appSource] is the
 * catalogue object the app loaded, so a reload that kept it keeps its adapter too.
 */
interface AppNovelSource : NovelSource {
    val appSource: Any
}

/**
 * Runs [block], a call into an app's catalogue, off the caller's thread, since an older extension fetches
 * on whichever thread asks. An app built against a class or method this build no longer ships throws a
 * LinkageError, which callers let through because it is not an Exception, so it leaves here as one: an
 * outdated app fails its call rather than crashing the whole app. The manga guard is in Mihon's own
 * files, at `UpdateMangaFromRemote` and `SourcePagingSource`.
 */
internal suspend fun <T> appSourceCall(block: suspend CoroutineScope.() -> T): T = withIOContext {
    try {
        block()
    } catch (e: LinkageError) {
        throw Exception(e.toString(), e)
    }
}

/**
 * One page of a listing or search, and whether the source says another follows. A format that cannot
 * say ends at its first empty page, so asking past the end costs nothing there; a tachiyomi source may
 * answer that with an error instead.
 */
data class NovelItemsPage(val items: List<NovelItem>, val hasNextPage: Boolean)
