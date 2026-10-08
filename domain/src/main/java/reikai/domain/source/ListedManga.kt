package reikai.domain.source

import eu.kanade.tachiyomi.source.model.MangasPage
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.model.Manga

/**
 * A page's entries, each once, in the source's order, told apart by [keyOf]. A source can list one
 * entry twice in a page, and both would be stored as one row, so every content type's page goes through this.
 */
fun <T> List<T>.listedOnce(keyOf: (T) -> String): List<T> = distinctBy(keyOf)

/** The manga a page of [sourceId] lists, by [listedOnce] on the url. */
fun MangasPage.listedManga(sourceId: Long): List<Manga> =
    mangas.map { it.toDomainManga(sourceId) }.listedOnce { it.url }
