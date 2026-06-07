package eu.kanade.tachiyomi.source

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.QuerySanitizer.sanitize
import kotlinx.coroutines.CoroutineExceptionHandler
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

    /**
     * Whether the source has support for latest updates.
     */
    val supportsLatest: Boolean

    /**
     * Get a page with a list of manga.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     */
    @Suppress("DEPRECATION")
    suspend fun getPopularManga(page: Int): MangasPage {
        return fetchPopularManga(page).awaitSingle()
    }

    /**
     * Get a page with a list of manga.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     * @param query the search query.
     * @param filters the list of filters to apply.
     */
    @Suppress("DEPRECATION")
    suspend fun getSearchManga(page: Int, query: String, filters: FilterList): MangasPage {
        return fetchSearchManga(page, query, filters).awaitSingle()
    }

    /**
     * Get a page with a list of latest manga updates.
     *
     * @since extensions-lib 1.5
     * @param page the page number to retrieve.
     */
    @Suppress("DEPRECATION")
    suspend fun getLatestUpdates(page: Int): MangasPage {
        return fetchLatestUpdates(page).awaitSingle()
    }

    /**
     * Returns the list of filters for the source.
     */
    fun getFilterList(): FilterList

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getPopularManga"),
    )
    fun fetchPopularManga(page: Int): Observable<MangasPage> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getSearchManga"),
    )
    fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> =
        throw IllegalStateException("Not used")

    @Deprecated(
        "Use the non-RxJava API instead",
        ReplaceWith("getLatestUpdates"),
    )
    fun fetchLatestUpdates(page: Int): Observable<MangasPage> =
        throw IllegalStateException("Not used")

    // RK -->

    /**
     * Whether parsing related mangas in manga page or extension provides a custom related-mangas request.
     * @default false
     * @since y2k/extensions-lib 1.6
     */
    val supportsRelatedMangas: Boolean get() = false

    /**
     * Disable the app's keyword-search fallback for related mangas on this source.
     * Useful when a source returns junk for short or two-word queries.
     * @default false
     * @since y2k/extensions-lib 1.6
     */
    val disableRelatedMangasBySearch: Boolean get() = false

    /**
     * Disable showing any related mangas for this source.
     * @default false
     * @since y2k/extensions-lib 1.6
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
     * @since y2k/extensions-lib 1.6
     */
    suspend fun getRelatedMangaList(
        manga: SManga,
        exceptionHandler: (Throwable) -> Unit,
        pushResults: suspend (relatedManga: Pair<String, List<SManga>>, completed: Boolean) -> Unit,
    ) {
        val handler = CoroutineExceptionHandler { _, e -> exceptionHandler(e) }
        if (!disableRelatedMangas) {
            supervisorScope {
                if (supportsRelatedMangas) launch(handler) { getRelatedMangaListByExtension(manga, exceptionHandler, pushResults) }
                if (!disableRelatedMangasBySearch) launch(handler) { getRelatedMangaListBySearch(manga, exceptionHandler, pushResults) }
            }
        }
    }

    /**
     * Get related mangas provided by the extension. Wraps [fetchRelatedMangaList] in a
     * [runCatching] and routes failures through [exceptionHandler] rather than throwing.
     *
     * @since y2k/extensions-lib 1.6
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
     * @since y2k/extensions-lib 1.6
     */
    suspend fun fetchRelatedMangaList(manga: SManga): List<SManga> =
        throw UnsupportedOperationException("Unsupported!")

    /**
     * Split and strip a manga title into searchable keywords for the search fallback.
     * Drops special characters, single-character tokens, and digit-only tokens.
     *
     * @since y2k/extensions-lib 1.6
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
            // exclude single-character tokens
            .filter { it.length > 1 }
    }

    /**
     * Keyword-search fallback: split the title into keywords, run [getSearchManga] for each,
     * and push hits as they come in. Used when the source has no native related-mangas support
     * (and [disableRelatedMangasBySearch] is `false`).
     *
     * @since y2k/extensions-lib 1.6
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
