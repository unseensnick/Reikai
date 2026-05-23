package yokai.presentation.library.manga

import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.ui.library.models.LibraryItem

/**
 * Phase 2 search helper. Faithful port of the legacy `LibraryMangaItem.filter()` matching rules
 * so users see the same hits in the Compose library that they did in the legacy one:
 *
 * - Tries title, author, artist, and source name as a free-text OR chain first.
 * - Falls through to a genre match. Comma-separated queries require **every** fragment to match
 *   a genre (so "action, romance" finds entries tagged with both). Single queries match one genre.
 * - `-tag` excludes that tag (so "-yaoi" only matches entries that do not have the yaoi tag).
 *
 * Pure function: source names are precomputed by the screen model (`Map<Long, String>`) so this
 * file has no Injekt dependency and stays unit-testable. `seriesType` matching from legacy is
 * intentionally deferred; add it when a user reports missing it.
 */
object MangaLibrarySearch {

    fun search(
        library: Map<Category, List<LibraryItem.Manga>>,
        query: String,
        sourceNames: Map<Long, String> = emptyMap(),
    ): Map<Category, List<LibraryItem.Manga>> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return library

        return library
            .mapValues { (_, items) -> items.filter { it.matches(trimmed, sourceNames) } }
            .filterValues { it.isNotEmpty() }
    }

    private fun LibraryItem.Manga.matches(query: String, sourceNames: Map<Long, String>): Boolean {
        val manga = libraryManga.manga
        val title = manga.title
        if (title.isBlank()) return query.isEmpty()

        if (title.contains(query, ignoreCase = true)) return true
        if (manga.author?.contains(query, ignoreCase = true) == true) return true
        if (manga.artist?.contains(query, ignoreCase = true) == true) return true
        val sourceName = sourceNames[manga.source].orEmpty()
        if (sourceName.contains(query, ignoreCase = true)) return true

        // Legacy splits genres by ", " (space included) to land clean fragments.
        val genres = manga.genre?.split(", ").orEmpty()
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
