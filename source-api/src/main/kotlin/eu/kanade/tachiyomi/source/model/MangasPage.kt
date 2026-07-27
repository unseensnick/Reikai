package eu.kanade.tachiyomi.source.model

// RK -->
import exh.metadata.metadata.RaisedSearchMetadata
// RK <--

// RK: open so the adult-source browse can return a MetadataMangasPage that carries per-gallery
//     metadata + a gallery-id paging cursor. Other sources keep using the plain MangasPage.
/* RK --> */ open /* RK <-- */ class MangasPage(val mangas: List<SManga>, val hasNextPage: Boolean) {

    @Deprecated("MangasPage is now a regular class")
    operator fun component1(): List<SManga> = mangas

    @Deprecated("MangasPage is now a regular class")
    operator fun component2(): Boolean = hasNextPage

    @Deprecated("MangasPage is now a regular class")
    fun copy(
        mangas: List<SManga> = this.mangas,
        hasNextPage: Boolean = this.hasNextPage,
    ): MangasPage = MangasPage(
        mangas = mangas,
        hasNextPage = hasNextPage,
    )
}

// RK: carrier for metadata sources (E-Hentai). `nextKey` is the source's own paging cursor
//     (the gallery id), used instead of a page-number increment; `mangasMetadata` rides alongside
//     for the rich browse rows (consumed later). Ported from Komikku's MetadataMangasPage.
class MetadataMangasPage(
    mangas: List<SManga>,
    hasNextPage: Boolean,
    val mangasMetadata: List<RaisedSearchMetadata>,
    val nextKey: Long? = null,
) : MangasPage(mangas, hasNextPage)
