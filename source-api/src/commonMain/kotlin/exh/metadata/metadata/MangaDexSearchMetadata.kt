package exh.metadata.metadata

import android.content.Context
import eu.kanade.tachiyomi.source.model.SManga
import exh.metadata.metadata.base.TrackerIdMetadata
import kotlinx.serialization.Serializable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

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

    override fun getExtraInfoPairs(context: Context): List<Pair<String, String>> {
        return with(context) {
            listOfNotNull(
                getItem(mdUuid) { stringResource(MR.strings.id) },
                getItem(cover) { stringResource(MR.strings.thumbnail_url) },
                getItem(title) { stringResource(MR.strings.title) },
                getItem(authors, { it.joinToString() }) { stringResource(MR.strings.author) },
                getItem(artists, { it.joinToString() }) { stringResource(MR.strings.artist) },
                getItem(langFlag) { stringResource(MR.strings.language) },
                getItem(lastChapterNumber) { stringResource(MR.strings.last_chapter_number) },
                getItem(rating) { stringResource(MR.strings.average_rating) },
                getItem(status) { stringResource(MR.strings.status) },
                getItem(followStatus) { stringResource(MR.strings.follow_status) },
                getItem(anilistId) { stringResource(MR.strings.anilist_id) },
                getItem(kitsuId) { stringResource(MR.strings.kitsu_id) },
                getItem(myAnimeListId) { stringResource(MR.strings.mal_id) },
                getItem(mangaUpdatesId) { stringResource(MR.strings.manga_updates_id) },
                getItem(animePlanetId) { stringResource(MR.strings.anime_planet_id) },
            )
        }
    }

    companion object {
        private const val TITLE_TYPE_MAIN = 0

        const val TAG_TYPE_DEFAULT = 0
    }
}
