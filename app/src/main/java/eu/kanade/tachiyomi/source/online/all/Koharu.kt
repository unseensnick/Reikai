package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.metadata.metadata.KoharuSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.DelegatedHttpSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Response

class Koharu(delegate: HttpSource, context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<KoharuSearchMetadata, Response>,
    NamespaceSource {
    override val metaClass = KoharuSearchMetadata::class
    override fun newMetaInstance() = KoharuSearchMetadata()
    override val lang = delegate.lang

    // capture gallery metadata on the details fetch, delegate chapters to the stock source.
    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            val response = client.newCall(mangaDetailsRequest(manga)).awaitSuccess()
            parseToManga(manga, response)
        } else {
            manga
        }
        val updatedChapters = if (fetchChapters) {
            delegate.getMangaUpdate(manga, chapters, fetchDetails = false, fetchChapters = true).chapters
        } else {
            chapters
        }
        return SMangaUpdate(updatedManga, updatedChapters)
    }

    override suspend fun parseIntoMetadata(metadata: KoharuSearchMetadata, input: Response) {
        val detail = jsonParser.decodeFromString<MangaDetail>(input.body.string())

        with(metadata) {
            title = detail.title

            val thumb = detail.thumbnails
            thumbnailUrl = if (thumb?.base != null && thumb.main?.path != null) {
                thumb.base + thumb.main.path
            } else {
                null
            }

            tags.clear()
            // Koharu encodes each tag's group as an integer namespace (see the stock KoharuDto).
            detail.tags.forEach { tag ->
                val namespace = NAMESPACE_BY_ID[tag.namespace] ?: KoharuSearchMetadata.TAGS_NAMESPACE
                // namespace 7 (uploader) carries an "anonymous" placeholder; drop it.
                if (tag.namespace == UPLOADER_ID && tag.name == "anonymous") return@forEach
                if (tag.name.isNotBlank()) {
                    tags += RaisedTag(namespace, tag.name, KoharuSearchMetadata.TAG_TYPE_DEFAULT)
                }
            }
        }
    }

    @Serializable
    data class MangaDetail(
        val title: String? = null,
        val tags: List<Tag> = emptyList(),
        val thumbnails: Thumbnails? = null,
    )

    @Serializable
    data class Tag(
        val name: String,
        val namespace: Int = 0,
    )

    @Serializable
    data class Thumbnails(
        val base: String? = null,
        val main: Thumbnail? = null,
    )

    @Serializable
    data class Thumbnail(
        val path: String? = null,
    )

    companion object {
        private const val UPLOADER_ID = 7

        private val NAMESPACE_BY_ID = mapOf(
            1 to KoharuSearchMetadata.ARTIST_NAMESPACE,
            2 to KoharuSearchMetadata.GROUP_NAMESPACE,
            3 to KoharuSearchMetadata.PARODY_NAMESPACE,
            4 to KoharuSearchMetadata.MAGAZINE_NAMESPACE,
            5 to KoharuSearchMetadata.CHARACTER_NAMESPACE,
            6 to KoharuSearchMetadata.COSPLAYER_NAMESPACE,
            UPLOADER_ID to KoharuSearchMetadata.UPLOADER_NAMESPACE,
            8 to KoharuSearchMetadata.MALE_NAMESPACE,
            9 to KoharuSearchMetadata.FEMALE_NAMESPACE,
            10 to KoharuSearchMetadata.MIXED_NAMESPACE,
            11 to KoharuSearchMetadata.LANGUAGE_NAMESPACE,
            12 to KoharuSearchMetadata.OTHER_NAMESPACE,
        )

        private val jsonParser = Json {
            ignoreUnknownKeys = true
        }
    }
}
