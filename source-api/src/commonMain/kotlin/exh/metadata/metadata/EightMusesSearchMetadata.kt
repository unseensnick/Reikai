package exh.metadata.metadata

import android.content.Context
import eu.kanade.tachiyomi.source.model.SManga
import exh.util.nullIfEmpty
import kotlinx.serialization.Serializable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

@Serializable
class EightMusesSearchMetadata : RaisedSearchMetadata() {
    var path: List<String> = emptyList()

    var title by titleDelegate(TITLE_TYPE_MAIN)

    var thumbnailUrl: String? = null

    override fun createMangaInfo(manga: SManga): SManga {
        val key = path.joinToString("/", prefix = "/")

        val title = title

        val cover = thumbnailUrl

        val artist = tags.ofNamespace(ARTIST_NAMESPACE).joinToString { it.name }

        val genres = tagsToGenreString()

        val description = null

        return manga.copy().also { m ->
            m.url = key
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
                getItem(title) { stringResource(MR.strings.title) },
                getItem(path.nullIfEmpty(), { it.joinToString("/", prefix = "/") }) {
                    stringResource(MR.strings.path)
                },
                getItem(thumbnailUrl) { stringResource(MR.strings.thumbnail_url) },
            )
        }
    }

    companion object {
        private const val TITLE_TYPE_MAIN = 0

        const val TAG_TYPE_DEFAULT = 0

        const val TAGS_NAMESPACE = "tags"
        const val ARTIST_NAMESPACE = "artist"
    }
}
