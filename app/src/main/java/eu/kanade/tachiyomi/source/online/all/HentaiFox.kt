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
import exh.metadata.metadata.HentaiFoxSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.DelegatedHttpSource
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class HentaiFox(delegate: HttpSource, context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<HentaiFoxSearchMetadata, Document>,
    NamespaceSource {
    override val metaClass = HentaiFoxSearchMetadata::class
    override fun newMetaInstance() = HentaiFoxSearchMetadata()
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

    override suspend fun parseIntoMetadata(metadata: HentaiFoxSearchMetadata, input: Document) {
        val root = input.selectFirst(".gallery_top") ?: return

        with(metadata) {
            title = root.selectFirst("h1")?.text()
            thumbnailUrl = root.selectFirst(".cover img")?.imgAttr()

            tags.clear()
            // HentaiFox renders each tag namespace as its own <ul class="<group>"> block.
            NAMESPACES.forEach { (cssClass, namespace) ->
                root.select("ul.$cssClass a").forEach { element ->
                    val name = element.ownText().trim()
                    if (name.isNotBlank()) {
                        tags += RaisedTag(namespace, name, HentaiFoxSearchMetadata.TAG_TYPE_DEFAULT)
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
        private val NAMESPACES = listOf(
            "artists" to HentaiFoxSearchMetadata.ARTIST_NAMESPACE,
            "groups" to HentaiFoxSearchMetadata.GROUP_NAMESPACE,
            "parodies" to HentaiFoxSearchMetadata.PARODY_NAMESPACE,
            "characters" to HentaiFoxSearchMetadata.CHARACTER_NAMESPACE,
            "tags" to HentaiFoxSearchMetadata.TAGS_NAMESPACE,
            "languages" to HentaiFoxSearchMetadata.LANGUAGE_NAMESPACE,
            "categories" to HentaiFoxSearchMetadata.CATEGORY_NAMESPACE,
        )
    }
}
