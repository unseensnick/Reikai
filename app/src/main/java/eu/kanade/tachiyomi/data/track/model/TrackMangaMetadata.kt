package eu.kanade.tachiyomi.data.track.model

/**
 * Metadata a tracker holds for a bound entry, used by the "Fill from tracker" editor action to
 * autofill the custom-info overlay. Ported from Komikku, with [genres] added: Komikku fetches no
 * genres, Reikai fills a clean genre list where the tracker exposes one.
 *
 * [authors] and [artists] are already comma-joined display strings, not lists.
 */
data class TrackMangaMetadata(
    val remoteId: Long? = null,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val description: String? = null,
    val authors: String? = null,
    val artists: String? = null,
    val genres: List<String>? = null,
)
