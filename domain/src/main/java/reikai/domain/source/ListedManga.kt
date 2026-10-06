package reikai.domain.source

import eu.kanade.tachiyomi.source.model.MangasPage
import mihon.domain.manga.model.toDomainManga
import tachiyomi.domain.manga.model.Manga

/**
 * The manga a page of [sourceId] lists, each once, in the source's order. A source can list one
 * url twice in a page, and both would be stored as one row.
 */
fun MangasPage.listedManga(sourceId: Long): List<Manga> =
    mangas.map { it.toDomainManga(sourceId) }.distinctBy { it.url }
