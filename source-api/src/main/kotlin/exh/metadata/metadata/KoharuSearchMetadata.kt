package exh.metadata.metadata

import android.content.Context
import eu.kanade.tachiyomi.source.model.SManga
import kotlinx.serialization.Serializable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

@Serializable
class KoharuSearchMetadata : RaisedSearchMetadata() {
    var title by titleDelegate(TITLE_TYPE_MAIN)

    var thumbnailUrl: String? = null

    override fun createMangaInfo(manga: SManga): SManga {
        val title = title

        val cover = thumbnailUrl

        val artists = tags.ofNamespace(ARTIST_NAMESPACE).joinToString { it.name }
        val groups = tags.ofNamespace(GROUP_NAMESPACE).joinToString { it.name }

        val genres = tagsToGenreString()

        return manga.copy().also { m ->
            m.title = title ?: manga.title
            m.thumbnail_url = cover ?: manga.thumbnail_url
            m.artist = artists
            m.author = groups.ifBlank { artists }
            m.genre = genres
        }
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
        const val MAGAZINE_NAMESPACE = "magazine"
        const val CHARACTER_NAMESPACE = "character"
        const val COSPLAYER_NAMESPACE = "cosplayer"
        const val UPLOADER_NAMESPACE = "uploader"
        const val MALE_NAMESPACE = "male"
        const val FEMALE_NAMESPACE = "female"
        const val MIXED_NAMESPACE = "mixed"
        const val LANGUAGE_NAMESPACE = "language"
        const val OTHER_NAMESPACE = "other"
        const val TAGS_NAMESPACE = "tags"
    }
}
