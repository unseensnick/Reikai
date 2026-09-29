package reikai.domain.source

import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import mihon.domain.manga.model.toDomainManga
import reikai.util.runCatchingCancellable
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.domain.manga.interactor.NetworkToLocalManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * Reads a shared link as a manga series for an extension without the link hooks: its search, then a stored
 * row, then a checked guess from the address, over the same [SharedLink] rules [ResolveNovelLink] uses.
 */
class ResolveMangaLink(
    private val onlineSources: suspend () -> List<HttpSource>,
    private val storedManga: suspend (url: String, sourceId: Long) -> Manga?,
    private val networkToLocalManga: suspend (Manga) -> Manga,
) {

    @Inject
    constructor(
        sourceManager: SourceManager,
        getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
        networkToLocalManga: NetworkToLocalManga,
    ) : this(
        { sourceManager.getOnlineSources() },
        { url, sourceId -> getMangaByUrlAndSourceId.await(url, sourceId) },
        { networkToLocalManga(it) },
    )

    /** A source that names the series: stored as [stored], or else [named] by its own address rule. */
    private class Match(val source: HttpSource, val stored: Manga?, val named: List<String>)

    /** The series in the link's site's own search for it, as the keiyoushi UrlActivity asks for it. */
    suspend fun bySearch(text: String): Manga? {
        val link = SharedLink.parse(text) ?: return null
        for (source in onlineSources().filter { link.relativeTo(it.baseUrl) != null }) {
            val results = runCatchingCancellable {
                source.getSearchManga(1, text.trim(), source.getFilterList()).mangas
            }.getOrNull() ?: continue
            val found = link.soleResult(results) { webUrl(source, it.url) } ?: continue
            return networkToLocalManga(found.toDomainManga(source.id))
        }
        return null
    }

    suspend fun byStoredRow(text: String): Manga? = single(text)?.stored

    suspend fun byGuess(text: String): Manga? {
        // A stored match names nothing, so it is never guessed again.
        val match = single(text) ?: return null
        val first = match.named.firstOrNull() ?: return null
        val (series, chapterPaths) = series(match.source, first) ?: return null
        val path = SharedLink.spelling(match.named, chapterPaths) ?: return null
        val parentTitle = SharedLink.parentOf(path)?.let { series(match.source, it)?.first?.title }
        if (parentTitle == series.title) return null
        return networkToLocalManga(series.copy(url = path))
    }

    private suspend fun single(text: String): Match? {
        val link = SharedLink.parse(text) ?: return null
        val matches = onlineSources().filter { link.relativeTo(it.baseUrl) != null }.mapNotNull { source ->
            val stored = link.storedSpellings(source.baseUrl).firstNotNullOfOrNull { storedManga(it, source.id) }
            val named = if (stored ==
                null
            ) {
                link.named(link.candidates(source.baseUrl)) { webUrl(source, it) }
            } else {
                emptyList()
            }
            Match(source, stored, named).takeIf { stored != null || named.isNotEmpty() }
        }
        return SharedLink.single(matches) { it.stored != null }
    }

    // An extension's own address rule may throw on a path it did not produce.
    private fun webUrl(source: HttpSource, path: String) =
        runCatching { source.getMangaUrl(SManga.create().apply { url = path }) }.getOrDefault("")

    /**
     * The series at [path] and the chapter paths its page gives, or null when the page is not one: no title
     * or no chapters.
     */
    private suspend fun series(source: HttpSource, path: String): Pair<Manga, List<String>>? = runCatchingCancellable {
        val update = source.getMangaUpdate(
            SManga.create().apply { url = path },
            chapters = emptyList(),
            fetchDetails = true,
            fetchChapters = true,
        )
        val title = runCatching { update.manga.title }.getOrNull()
        if (title.isNullOrBlank() || update.chapters.isEmpty()) return@runCatchingCancellable null
        update.manga.apply { url = path }.toDomainManga(source.id) to update.chapters.map { it.url }
    }.getOrNull()
}
