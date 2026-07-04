package exh.metadata.metadata

import android.content.Context
import eu.kanade.tachiyomi.source.model.SManga
import exh.metadata.metadata.base.TrackerIdMetadata
import kotlinx.serialization.Serializable

@Serializable
class MangaDexSearchMetadata : RaisedSearchMetadata(), TrackerIdMetadata {
    var mdUuid: String? = null

    var cover: String? = null

    var title: String? by titleDelegate(TITLE_TYPE_MAIN)
    var altTitles: List<String>? = null

    var description: String? = null

    var authors: List<String>? = null
    var artists: List<String>? = null

    var langFlag: String? = null

    var lastChapterNumber: Int? = null
    var rating: Float? = null

    override var anilistId: String? = null
    override var kitsuId: String? = null
    override var myAnimeListId: String? = null
    override var mangaUpdatesId: String? = null
    override var animePlanetId: String? = null

    var status: Int? = null

    var followStatus: Int? = null

    // `relation: MangaDexRelation?` arrives in Phase 6 with the similar/relations feature.

    override fun createMangaInfo(manga: SManga): SManga {
        val key = mdUuid?.let { "/manga/$it" }
        val title = title
        val cover = cover
        val author = authors?.joinToString()
        val artist = artists?.joinToString()
        val status = status
        val genres = tagsToGenreString()
        val description = description

        return manga.copy().also { m ->
            m.url = key ?: manga.url
            m.title = title ?: manga.title
            m.thumbnail_url = cover ?: manga.thumbnail_url
            m.author = author ?: manga.author
            m.artist = artist ?: manga.artist
            m.status = status ?: manga.status
            m.genre = genres
            m.description = description ?: manga.description
        }
    }

    // Phase 1 stub. The full namespaced info rows (and the i18n strings they need) land in
    // Phase 2 with the GalleryInfoBox rendering; empty keeps the generic fallback quiet until then.
    override fun getExtraInfoPairs(context: Context): List<Pair<String, String>> = emptyList()

    companion object {
        private const val TITLE_TYPE_MAIN = 0

        const val TAG_TYPE_DEFAULT = 0
    }
}
