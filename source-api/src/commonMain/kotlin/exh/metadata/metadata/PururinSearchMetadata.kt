package exh.metadata.metadata

import android.content.Context
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

@Serializable
class PururinSearchMetadata : RaisedSearchMetadata() {
    var prId: Int? = null

    var prShortLink: String? = null

    var title by titleDelegate(TITLE_TYPE_TITLE)
    var altTitle by titleDelegate(TITLE_TYPE_ALT_TITLE)

    var thumbnailUrl: String? = null

    var uploaderDisp: String? = null

    var pages: Int? = null

    var fileSize: String? = null

    var ratingCount: Int? = null
    var averageRating: Double? = null

    override fun createMangaInfo(manga: SManga): SManga {
        val key = prId?.let { prId ->
            prShortLink?.let { prShortLink ->
                "/gallery/$prId/$prShortLink"
            }
        }

        val title = title ?: altTitle

        val cover = thumbnailUrl

        val artist = tags.ofNamespace(TAG_NAMESPACE_ARTIST).joinToString { it.name }

        val genres = tagsToGenreString()

        val description = null

        return manga.copy().also { m ->
            m.url = key ?: manga.url
            m.title = title ?: manga.title
            m.thumbnail_url = cover ?: manga.thumbnail_url
            m.artist = artist
            m.genre = genres
            m.description = description
        }
    }

    override fun getExtraInfoPairs(context: Context): List<Pair<String, String>> {
        return with(context) {
            listOfNotNull(
                getItem(prId) { stringResource(MR.strings.id) },
                getItem(title) { stringResource(MR.strings.title) },
                getItem(altTitle) { stringResource(MR.strings.alt_title) },
                getItem(thumbnailUrl) { stringResource(MR.strings.thumbnail_url) },
                getItem(uploaderDisp) { stringResource(MR.strings.uploader_capital) },
                getItem(uploader) { stringResource(MR.strings.uploader) },
                getItem(pages) { stringResource(MR.strings.page_count) },
                getItem(fileSize) { stringResource(MR.strings.gallery_size) },
                getItem(ratingCount) { stringResource(MR.strings.total_ratings) },
                getItem(averageRating) { stringResource(MR.strings.average_rating) },
            )
        }
    }

    companion object {
        private const val TITLE_TYPE_TITLE = 0
        private const val TITLE_TYPE_ALT_TITLE = 1

        const val TAG_TYPE_DEFAULT = 0

        private const val TAG_NAMESPACE_ARTIST = "artist"
        const val TAG_NAMESPACE_CATEGORY = "category"

        const val BASE_URL = "https://pururin.me"
    }
}
