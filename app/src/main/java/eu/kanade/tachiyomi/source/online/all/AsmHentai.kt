package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.util.asJsoup
import exh.metadata.metadata.AsmHentaiSearchMetadata
import exh.metadata.metadata.GallerySiteSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.DelegatedHttpSource
import exh.source.layeredMangaUpdate
import exh.util.lazyImageUrl
import org.jsoup.nodes.Document

class AsmHentai(delegate: HttpSource, context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<AsmHentaiSearchMetadata, Document>,
    NamespaceSource {
    override val metaClass = AsmHentaiSearchMetadata::class
    override fun newMetaInstance() = AsmHentaiSearchMetadata()

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

    override suspend fun parseIntoMetadata(metadata: AsmHentaiSearchMetadata, input: Document) {
        val root = input.selectFirst(".book_page") ?: return

        with(metadata) {
            title = root.selectFirst("h1")?.text()
            thumbnailUrl = root.selectFirst(".cover img")?.lazyImageUrl()

            tags.clear()
            // AsmHentai groups tags under labelled `.tags` blocks: `.tags:contains(<Label>:)`.
            NAMESPACES.forEach { (label, namespace) ->
                root.select(".tags:contains($label:) .tag_list a").forEach { element ->
                    val name = element.selectFirst(".tag")?.ownText()?.trim().orEmpty()
                    if (name.isNotBlank()) {
                        tags += RaisedTag(namespace, name, GallerySiteSearchMetadata.TAG_TYPE_DEFAULT)
                    }
                }
            }
        }
    }

    companion object {
        // Label text shown before each tag group on AsmHentai detail pages -> our namespace.
        private val NAMESPACES = listOf(
            "Tags" to GallerySiteSearchMetadata.TAGS_NAMESPACE,
            "Artists" to GallerySiteSearchMetadata.ARTIST_NAMESPACE,
            "Groups" to GallerySiteSearchMetadata.GROUP_NAMESPACE,
            "Parodies" to GallerySiteSearchMetadata.PARODY_NAMESPACE,
            "Characters" to GallerySiteSearchMetadata.CHARACTER_NAMESPACE,
            "Languages" to GallerySiteSearchMetadata.LANGUAGE_NAMESPACE,
            "Categories" to GallerySiteSearchMetadata.CATEGORY_NAMESPACE,
            "Category" to GallerySiteSearchMetadata.CATEGORY_NAMESPACE,
        )
    }
}
