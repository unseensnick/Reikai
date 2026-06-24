package eu.kanade.tachiyomi.source.online.english

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.source.online.UrlImportableSource
import eu.kanade.tachiyomi.util.asJsoup
import exh.metadata.metadata.PururinSearchMetadata
import exh.metadata.metadata.RaisedSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.source.DelegatedHttpSource
import exh.util.dropBlank
import exh.util.trimAll
import org.jsoup.nodes.Document

class Pururin(delegate: HttpSource, val context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<PururinSearchMetadata, Document>,
    UrlImportableSource,
    NamespaceSource {
    /**
     * An ISO 639-1 compliant language code (two letters in lower case).
     */
    override val lang = "en"

    /**
     * The class of the metadata used by this source
     */
    override val metaClass = PururinSearchMetadata::class
    override fun newMetaInstance() = PururinSearchMetadata()

    // RK: capture gallery metadata on the details fetch, delegate chapters to the stock source.
    // URL import (Komikku's fetchSearchManga override) is deferred with GalleryAdder.
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

    override suspend fun parseIntoMetadata(metadata: PururinSearchMetadata, input: Document) {
        val selfLink = input.select("[itemprop=name]").last()!!.parent()
        val parsedSelfLink = selfLink!!.attr("href").toUri().pathSegments

        with(metadata) {
            prId = parsedSelfLink[parsedSelfLink.lastIndex - 1].toInt()
            prShortLink = parsedSelfLink.last()

            val contentWrapper = input.selectFirst(".content-wrapper")
            title = contentWrapper!!.selectFirst(".title h1")!!.text()
            altTitle = contentWrapper.selectFirst(".alt-title")?.text()

            thumbnailUrl = "https:" + input.selectFirst(".cover-wrapper v-lazy-image")!!.attr("src")

            tags.clear()
            contentWrapper.select(".table-gallery-info > tbody > tr").forEach { ele ->
                val key = ele.child(0).text().lowercase()
                val value = ele.child(1)
                when (key) {
                    "pages" -> {
                        val split = value.text().split("(").trimAll().dropBlank()

                        pages = split.first().toIntOrNull()
                        fileSize = split.last().removeSuffix(")").trim()
                    }
                    "ratings" -> {
                        ratingCount = value.selectFirst("[itemprop=ratingCount]")!!.attr("content").toIntOrNull()
                        averageRating = value.selectFirst("[itemprop=ratingValue]")!!.attr("content").toDoubleOrNull()
                    }
                    "uploader" -> {
                        uploaderDisp = value.text()
                        uploader = value.child(0).attr("href").toUri().lastPathSegment
                    }
                    else -> {
                        value.select("a").forEach { link ->
                            val searchUrl = link.attr("href").toUri()
                            val namespace = searchUrl.pathSegments[searchUrl.pathSegments.lastIndex - 2]
                            tags += RaisedTag(
                                namespace,
                                searchUrl.lastPathSegment!!.substringBefore("."),
                                if (namespace != PururinSearchMetadata.TAG_NAMESPACE_CATEGORY) {
                                    PururinSearchMetadata.TAG_TYPE_DEFAULT
                                } else {
                                    RaisedSearchMetadata.TAG_TYPE_VIRTUAL
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    override val matchingHosts = listOf(
        "pururin.me",
    )

    override suspend fun mapUrlToMangaUrl(uri: Uri): String {
        return "${PururinSearchMetadata.BASE_URL}/gallery/${uri.pathSegments.getOrNull(1)}/${uri.lastPathSegment}"
    }
}
