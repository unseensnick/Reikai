package exh.metadata.metadata

import android.content.Context
import eu.kanade.tachiyomi.source.model.SManga
import exh.metadata.MetadataUtil
import kotlinx.serialization.Serializable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

@Serializable
class NHentaiSearchMetadata : RaisedSearchMetadata() {
    var url get() = nhId?.let { BASE_URL + nhIdToPath(it) }
        set(a) {
            a?.let {
                nhId = nhUrlToId(a)
            }
        }

    var nhId: Long? = null

    var uploadDate: Long? = null

    var favoritesCount: Long? = null

    var mediaId: String? = null

    var japaneseTitle by titleDelegate(TITLE_TYPE_JAPANESE)
    var englishTitle by titleDelegate(TITLE_TYPE_ENGLISH)
    var shortTitle by titleDelegate(TITLE_TYPE_SHORT)

    var coverImageUrl: String? = null
    var pageImagePreviewUrls: List<String> = emptyList()

    var scanlator: String? = null

    var preferredTitle: Int? = null

    override fun createMangaInfo(manga: SManga): SManga {
        val key = nhId?.let { nhIdToPath(it) }

        val title = when (preferredTitle) {
            TITLE_TYPE_SHORT -> shortTitle ?: englishTitle ?: japaneseTitle ?: manga.title
            0, TITLE_TYPE_ENGLISH -> englishTitle ?: japaneseTitle ?: shortTitle ?: manga.title
            else -> englishTitle ?: japaneseTitle ?: shortTitle ?: manga.title
        }

        // Set artist (if we can find one)
        val artist = tags.ofNamespace(NHENTAI_ARTIST_NAMESPACE).let { tags ->
            if (tags.isNotEmpty()) tags.joinToString(transform = { it.name }) else null
        }

        // Set group (if we can find one)
        val group = tags.ofNamespace(NHENTAI_GROUP_NAMESPACE).let { tags ->
            if (tags.isNotEmpty()) tags.joinToString(transform = { it.name }) else null
        }

        // Copy tags -> genres
        val genres = tagsToGenreString()

        // Try to automatically identify if it is ongoing, we try not to be too lenient here to avoid making mistakes
        // We default to completed
        var status = SManga.COMPLETED
        englishTitle?.let { t ->
            MetadataUtil.ONGOING_SUFFIX.find {
                t.endsWith(it, ignoreCase = true)
            }?.let {
                status = SManga.ONGOING
            }
        }

        return manga.copy().also { m ->
            m.url = key ?: manga.url
            m.thumbnail_url = coverImageUrl ?: manga.thumbnail_url
            m.title = title
            m.artist = group ?: manga.artist
            m.author = artist ?: manga.artist
            m.genre = genres
            m.status = status
            m.description = null
        }
    }

    override fun getExtraInfoPairs(context: Context): List<Pair<String, String>> {
        return with(context) {
            listOfNotNull(
                getItem(nhId) { stringResource(MR.strings.id) },
                getItem(
                    uploadDate,
                    {
                        MetadataUtil.EX_DATE_FORMAT
                            .format(ZonedDateTime.ofInstant(Instant.ofEpochSecond(it), ZoneId.systemDefault()))
                    },
                ) {
                    stringResource(MR.strings.date_posted)
                },
                getItem(favoritesCount) { stringResource(MR.strings.total_favorites) },
                getItem(mediaId) { stringResource(MR.strings.media_id) },
                getItem(japaneseTitle) { stringResource(MR.strings.japanese_title) },
                getItem(englishTitle) { stringResource(MR.strings.english_title) },
                getItem(shortTitle) { stringResource(MR.strings.short_title) },
                getItem(coverImageUrl) { stringResource(MR.strings.thumbnail_url) },
                getItem(pageImagePreviewUrls.size) { stringResource(MR.strings.page_count) },
                getItem(scanlator) { stringResource(MR.strings.scanlator) },
            )
        }
    }

    companion object {
        private const val TITLE_TYPE_JAPANESE = 0
        const val TITLE_TYPE_ENGLISH = 1
        const val TITLE_TYPE_SHORT = 2

        const val TAG_TYPE_DEFAULT = 0

        const val BASE_URL = "https://nhentai.net"

        private const val NHENTAI_ARTIST_NAMESPACE = "artist"
        private const val NHENTAI_GROUP_NAMESPACE = "group"
        const val NHENTAI_CATEGORIES_NAMESPACE = "category"

        fun nhUrlToId(url: String) =
            url.split("/").last { it.isNotBlank() }.toLong()

        fun nhIdToPath(id: Long) = "/g/$id/"
    }
}
