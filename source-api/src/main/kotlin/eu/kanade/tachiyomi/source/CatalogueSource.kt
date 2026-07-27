package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.util.QuerySanitizer.sanitize
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import rx.Observable
import tachiyomi.core.common.util.lang.awaitSingle

interface CatalogueSource : Source {

    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang: String

    @Suppress("DEPRECATION")
    override suspend fun getPopularManga(page: Int): MangasPage = fetchPopularManga(page).awaitSingle()

    @Suppress("DEPRECATION")
    override suspend fun getLatestUpdates(page: Int): MangasPage = fetchLatestUpdates(page).awaitSingle()

    @Suppress("DEPRECATION")
    override suspend fun getSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): MangasPage = fetchSearchManga(page, query, filters).awaitSingle()

    @Suppress("DEPRECATION")
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = supervisorScope {
        val asyncManga = if (fetchDetails) async { fetchMangaDetails(manga).awaitSingle() } else null
        val asyncChapters = if (fetchChapters) async { fetchChapterList(manga).awaitSingle() } else null
        SMangaUpdate(asyncManga?.await() ?: manga, asyncChapters?.await() ?: chapters)
    }

    @Suppress("DEPRECATION")
    override suspend fun getPageList(chapter: SChapter): List<Page> = fetchPageList(chapter).awaitSingle()

    /**
     * Returns an observable containing a page with a list of manga.
     *
     * @param page the page number to retrieve.
     */
    @Deprecated("Use the suspend API instead", ReplaceWith("getPopularManga"))
    fun fetchPopularManga(page: Int): Observable<MangasPage> = throw UnsupportedOperationException()

    /**
     * Returns an observable containing a page with a list of manga.
     *
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    @Deprecated("Use the suspend API instead", ReplaceWith("getSearchManga"))
    fun fetchSearchManga(
        page: Int,
        query: String,
        filters: FilterList,
    ): Observable<MangasPage> = throw UnsupportedOperationException()

    /**
     * Returns an observable containing a page with a list of latest manga updates.
     *
     * @param page the page number to retrieve.
     */
    @Deprecated("Use the suspend API instead", ReplaceWith("getLatestUpdates"))
    fun fetchLatestUpdates(page: Int): Observable<MangasPage> = throw UnsupportedOperationException()

    // RK -->

    /**
     * Whether parsing related mangas in manga page or extension provides a custom related-mangas request.
     * @default false
     * @since reikai/extensions-lib 1.6
     */
    val supportsRelatedMangas: Boolean get() = false

    /**
     * Disable the app's keyword-search fallback for related mangas on this source.
     * Useful when a source returns junk for short or two-word queries.
     * @default false
     * @since reikai/extensions-lib 1.6
     */
    val disableRelatedMangasBySearch: Boolean get() = false

    /**
     * Disable showing any related mangas for this source.
     * @default false
     * @since reikai/extensions-lib 1.6
     */
    val disableRelatedMangas: Boolean get() = false

    /**
     * Get all available related mangas for a manga. Normally it is not necessary to override
     * this method, extensions should override [fetchRelatedMangaList] and toggle [supportsRelatedMangas].
     *
     * Results are streamed as `(keyword, mangas)` pairs via [pushResults] so the UI can populate
     * incrementally as each keyword search returns.
     *
     * @param manga the current manga to get related mangas for.
     * @param exceptionHandler invoked for unrecoverable errors at the top level of the search graph.
     * @param pushResults called once per keyword bucket; `completed` is reserved for a future signal.
     * @since reikai/extensions-lib 1.6
     */
    suspend fun getRelatedMangaList(
        manga: SManga,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedManga: Pair<String, List<SManga>>, completed: Boolean) -> Unit,
    ) {
        val handler = CoroutineExceptionHandler { _, e -> exceptionHandler(e) }
        if (!disableRelatedMangas) {
            supervisorScope {
                if (supportsRelatedMangas) {
                    launch(handler) {
                        getRelatedMangaListByExtension(manga, exceptionHandler, pushResults)
                    }
                }
                if (!disableRelatedMangasBySearch) {
                    launch(handler) {
                        getRelatedMangaListBySearch(manga, exceptionHandler, pushResults)
                    }
                }
            }
        }
    }

    /**
     * Get related mangas provided by the extension. Wraps [fetchRelatedMangaList] in a
     * [runCatching] and routes failures through [exceptionHandler] rather than throwing.
     *
     * @since reikai/extensions-lib 1.6
     */
    suspend fun getRelatedMangaListByExtension(
        manga: SManga,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedManga: Pair<String, List<SManga>>, completed: Boolean) -> Unit,
    ) {
        runCatching { fetchRelatedMangaList(manga) }
            .onSuccess { if (it.isNotEmpty()) pushResults(Pair("", it), false) }
            .onFailure { exceptionHandler(it) }
    }

    /**
     * Override this in an extension to provide native related-mangas. Default throws.
     * Only called when [supportsRelatedMangas] is `true`.
     *
     * @since reikai/extensions-lib 1.6
     */
    suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> =
        throw UnsupportedOperationException("Unsupported!")

    /**
     * Split and strip a manga title into searchable keywords for the search fallback.
     * Drops special characters, digit-only tokens, stop words, and tokens too short to be
     * distinctive, then keeps only the longest few.
     *
     * Each keyword costs one search request against the source, so the count is capped: a long
     * light-novel title would otherwise fan out to a dozen concurrent searches and starve the
     * chapter list on a rate-limited source. Short and common words are dropped rather than
     * truncated at random, because searching "the" returns noise, not related manga.
     *
     * @since reikai/extensions-lib 1.6
     */
    fun String.stripKeywordForRelatedMangas(): List<String> {
        val regexWhitespace = Regex("\\s+")
        val regexSpecialCharacters =
            Regex("([!~#$%^&*+_|/\\\\,?:;'“”‘’\"<>(){}\\[\\]。・～：—！？、―«»《》〘〙【】「」｜]|\\s-|-\\s|\\s\\.|\\.\\s)")
        val regexNumberOnly = Regex("^\\d+$")

        return replace(regexSpecialCharacters, " ")
            .split(regexWhitespace)
            .map {
                // remove number-only tokens
                it.replace(regexNumberOnly, "")
                    .lowercase()
            }
            .filter { it.length > MIN_KEYWORD_LENGTH && it !in RELATED_SEARCH_STOP_WORDS }
            .distinct()
            .sortedByDescending { it.length }
            .take(MAX_KEYWORDS_FOR_RELATED_MANGAS)
    }

    /**
     * Keyword-search fallback: split the title into keywords, run [getSearchManga] for each,
     * and push hits as they come in. Used when the source has no native related-mangas support
     * (and [disableRelatedMangasBySearch] is `false`).
     *
     * @since reikai/extensions-lib 1.6
     */
    suspend fun getRelatedMangaListBySearch(
        manga: SManga,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedManga: Pair<String, List<SManga>>, completed: Boolean) -> Unit,
    ) {
        val words = HashSet<String>()
        words.add(manga.title)
        manga.title.stripKeywordForRelatedMangas()
            .filterNot { word -> words.any { it.lowercase() == word } }
            .onEach { words.add(it) }
        if (words.isEmpty()) return

        coroutineScope {
            val filterList = getFilterList()
            words.map { keyword ->
                launch {
                    runCatching {
                        getSearchManga(1, keyword.sanitize(), filterList).mangas
                    }
                        .onSuccess { if (it.isNotEmpty()) pushResults(Pair(keyword, it), false) }
                        .onFailure { exceptionHandler(it) }
                }
            }
        }
    }
    // RK <--
}

// RK --> related-mangas keyword-search fallback tuning

/** Tokens at or below this length carry no search signal ("in", "wa", "no"). */
private const val MIN_KEYWORD_LENGTH = 2

/**
 * Keyword searches issued per details open, on top of the full-title search. Four is enough to
 * surface a series' siblings while keeping the burst small enough not to starve the chapter list
 * on a source that rate-limits itself.
 */
private const val MAX_KEYWORDS_FOR_RELATED_MANGAS = 3

/**
 * Words common enough that searching them returns the source's whole catalogue rather than
 * anything related. English only: other languages' particles are short enough that
 * [MIN_KEYWORD_LENGTH] already drops them.
 */
private val RELATED_SEARCH_STOP_WORDS = setOf(
    "the", "and", "for", "with", "from", "into", "onto", "out", "not",
    "you", "your", "his", "her", "their", "its", "our", "who", "what", "when", "where",
    "was", "were", "are", "been", "has", "have", "had", "did", "does",
    "this", "that", "these", "those", "there", "here", "than", "then",
    "but", "all", "any", "can", "will", "just", "now", "how", "why",
)

// RK <--
