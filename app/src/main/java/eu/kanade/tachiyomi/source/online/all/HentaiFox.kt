package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.util.asJsoup
import exh.metadata.metadata.GallerySiteSearchMetadata
import exh.metadata.metadata.HentaiFoxSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.DelegatedHttpSource
import exh.source.layeredMangaUpdate
import exh.util.lazyImageUrl
import org.jsoup.nodes.Document

class HentaiFox(delegate: HttpSource, context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<HentaiFoxSearchMetadata, Document>,
    NamespaceSource {
    override val metaClass = HentaiFoxSearchMetadata::class
    override fun newMetaInstance() = HentaiFoxSearchMetadata()

    override fun tagSearchQuery(namespace: String, tag: String) = tag
    override val lang = delegate.lang

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate = layeredMangaUpdate(manga, chapters, fetchDetails, fetchChapters) { base, response ->
        parseToManga(base, response.asJsoup())
    }

    override suspend fun parseIntoMetadata(metadata: HentaiFoxSearchMetadata, input: Document) {
        val root = input.selectFirst(".gallery_top") ?: return

        with(metadata) {
            title = root.selectFirst("h1")?.text()
            thumbnailUrl = root.selectFirst(".cover img")?.lazyImageUrl()

            tags.clear()
            // HentaiFox renders each tag namespace as its own <ul class="<group>"> block.
            NAMESPACES.forEach { (cssClass, namespace) ->
                root.select("ul.$cssClass a").forEach { element ->
                    val name = element.ownText().trim()
                    if (name.isNotBlank()) {
                        tags += RaisedTag(namespace, name, GallerySiteSearchMetadata.TAG_TYPE_DEFAULT)
                    }
                }
            }
        }
    }

    companion object {
        private val NAMESPACES = listOf(
            "artists" to GallerySiteSearchMetadata.ARTIST_NAMESPACE,
            "groups" to GallerySiteSearchMetadata.GROUP_NAMESPACE,
            "parodies" to GallerySiteSearchMetadata.PARODY_NAMESPACE,
            "characters" to GallerySiteSearchMetadata.CHARACTER_NAMESPACE,
            "tags" to GallerySiteSearchMetadata.TAGS_NAMESPACE,
            "languages" to GallerySiteSearchMetadata.LANGUAGE_NAMESPACE,
            "categories" to GallerySiteSearchMetadata.CATEGORY_NAMESPACE,
        )
    }
}
