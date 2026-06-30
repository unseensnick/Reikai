package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.util.asJsoup
import exh.metadata.metadata.AsmHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.DelegatedHttpSource
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class AsmHentai(delegate: HttpSource, context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<AsmHentaiSearchMetadata, Document>,
    NamespaceSource {
    override val metaClass = AsmHentaiSearchMetadata::class
    override fun newMetaInstance() = AsmHentaiSearchMetadata()
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
            parseToManga(manga, response.asJsoup())
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

    override suspend fun parseIntoMetadata(metadata: AsmHentaiSearchMetadata, input: Document) {
        val root = input.selectFirst(".book_page") ?: return

        with(metadata) {
            title = root.selectFirst("h1")?.text()
            thumbnailUrl = root.selectFirst(".cover img")?.imgAttr()

            tags.clear()
            // AsmHentai groups tags under labelled `.tags` blocks: `.tags:contains(<Label>:)`.
            NAMESPACES.forEach { (label, namespace) ->
                root.select(".tags:contains($label:) .tag_list a").forEach { element ->
                    val name = element.selectFirst(".tag")?.ownText()?.trim().orEmpty()
                    if (name.isNotBlank()) {
                        tags += RaisedTag(namespace, name, AsmHentaiSearchMetadata.TAG_TYPE_DEFAULT)
                    }
                }
            }
        }
    }

    private fun Element.imgAttr(): String? = when {
        hasAttr("data-src") -> absUrl("data-src")
        hasAttr("data-cfsrc") -> absUrl("data-cfsrc")
        else -> absUrl("src")
    }.ifBlank { null }

    companion object {
        // Label text shown before each tag group on AsmHentai detail pages -> our namespace.
        private val NAMESPACES = listOf(
            "Tags" to AsmHentaiSearchMetadata.TAGS_NAMESPACE,
            "Artists" to AsmHentaiSearchMetadata.ARTIST_NAMESPACE,
            "Groups" to AsmHentaiSearchMetadata.GROUP_NAMESPACE,
            "Parodies" to AsmHentaiSearchMetadata.PARODY_NAMESPACE,
            "Characters" to AsmHentaiSearchMetadata.CHARACTER_NAMESPACE,
            "Languages" to AsmHentaiSearchMetadata.LANGUAGE_NAMESPACE,
            "Categories" to AsmHentaiSearchMetadata.CATEGORY_NAMESPACE,
            "Category" to AsmHentaiSearchMetadata.CATEGORY_NAMESPACE,
        )
    }
}
