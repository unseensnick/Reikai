package yokai.presentation.library.novels

import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Novel-side parallel of [yokai.presentation.library.manga.MangaLibrarySearch]. Faithful port of
 * the matching rules:
 *
 * - Tries title, author, artist, and source name as a free-text OR chain first.
 * - Falls through to a genre match. Comma-separated queries require **every** fragment to match
 *   a genre. Single queries match one genre.
 * - `-tag` excludes that tag.
 *
 * Diverges from the manga helper in two places:
 *
 * - **No `seriesTypes` parameter.** Novels have no series-type dimension (manhwa / webtoon /
 *   comic / etc.); the genre-as-only comparand is sufficient.
 * - **`sourceNames` key type is `String`**, not `Long`, because lnreader plugin ids are strings.
 *
 * Pure function: source names are precomputed by the screen model so this file has no Injekt or
 * Context dependency and stays unit-testable.
 */
object NovelLibrarySearch {

    fun search(
        library: Map<NovelCategory, List<LibraryItem.Novel>>,
        query: String,
        sourceNames: Map<String, String> = emptyMap(),
    ): Map<NovelCategory, List<LibraryItem.Novel>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return library

        return library
            .mapValues { (_, items) -> items.filter { it.matches(trimmed, sourceNames) } }
            .filterValues { it.isNotEmpty() }
    }

    private fun LibraryItem.Novel.matches(
        query: String,
        sourceNames: Map<String, String>,
    ): Boolean {
        val novel = libraryNovel.novel
        val title = novel.title
        if (title.isBlank()) return query.isEmpty()

        if (title.contains(query, ignoreCase = true)) return true
        if (novel.author?.contains(query, ignoreCase = true) == true) return true
        if (novel.artist?.contains(query, ignoreCase = true) == true) return true
        val sourceName = sourceNames[novel.source].orEmpty()
        if (sourceName.contains(query, ignoreCase = true)) return true

        // Novel.genres is already a List<String> (NovelMapping splits the source's joined string).
        val genres = novel.genres.orEmpty()
        return if (query.contains(",")) {
            query.split(",").all { containsGenre(it.trim(), genres) }
        } else {
            containsGenre(query, genres)
        }
    }

    private fun containsGenre(tag: String, genres: List<String>): Boolean {
        if (tag.isEmpty()) return true
        return if (tag.startsWith("-")) {
            val realTag = tag.substringAfter("-")
            genres.none { it.trim().equals(realTag, ignoreCase = true) }
        } else {
            genres.any { it.trim().equals(tag, ignoreCase = true) }
        }
    }
}
