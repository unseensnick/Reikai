package exh.metadata.metadata

import android.content.Context
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * The metadata of a gallery site whose wrapper lays a title, a cover and tags over the extension's
 * own details, so it leaves the extension's author alone. Each subclass's name is written into its
 * stored rows as their type.
 */
@Serializable
sealed class GallerySiteSearchMetadata : RaisedSearchMetadata() {
    var title by titleDelegate(TITLE_TYPE_MAIN)

    var thumbnailUrl: String? = null

    override fun createMangaInfo(manga: SManga): SManga = manga.copy().also { m ->
        m.title = title ?: manga.title
        m.thumbnail_url = thumbnailUrl ?: manga.thumbnail_url
        m.artist = tags.ofNamespace(ARTIST_NAMESPACE).joinToString { it.name }
        m.genre = tagsToGenreString()
    }

    override fun getExtraInfoPairs(context: Context): List<Pair<String, String>> {
        return with(context) {
            listOfNotNull(
                getItem(title) { stringResource(MR.strings.title) },
                getItem(thumbnailUrl) { stringResource(MR.strings.thumbnail_url) },
            )
        }
    }

    companion object {
        private const val TITLE_TYPE_MAIN = 0

        const val TAG_TYPE_DEFAULT = 0

        const val ARTIST_NAMESPACE = "artist"
        const val GROUP_NAMESPACE = "group"
        const val PARODY_NAMESPACE = "parody"
        const val CHARACTER_NAMESPACE = "character"
        const val TAGS_NAMESPACE = "tags"
        const val LANGUAGE_NAMESPACE = "language"
        const val CATEGORY_NAMESPACE = "category"
    }
}
