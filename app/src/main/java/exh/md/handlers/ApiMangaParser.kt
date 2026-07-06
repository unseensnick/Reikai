package exh.md.handlers

import eu.kanade.tachiyomi.source.model.SManga
import exh.md.dto.MangaDto
import exh.md.dto.StatisticsMangaDto
import exh.md.utils.MdConstants
import exh.md.utils.MdUtil
import exh.md.utils.asMdMap
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import exh.util.capitalize
import exh.util.nullIfEmpty
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import java.util.Locale
import kotlin.math.floor

// Details/metadata parse only. The metadata round-trip (fetch-or-load, store, createMangaInfo) is
// owned by the MetadataSource interface, so this does not inject the FlatMetadata interactors the
// way Komikku's ApiMangaParser does. Chapter parsing arrives when chapters stop delegating.
class ApiMangaParser(
    private val lang: String,
) {
    fun parseIntoMetadata(
        metadata: MangaDexSearchMetadata,
        mangaDto: MangaDto,
        simpleChapters: List<String>,
        statistics: StatisticsMangaDto?,
        coverQuality: String,
        preferExtensionLangTitle: Boolean,
    ) {
        with(metadata) {
            try {
                val mangaAttributesDto = mangaDto.data.attributes
                mdUuid = mangaDto.data.id
                title = MdUtil.getTitleFromManga(mangaAttributesDto, lang, preferExtensionLangTitle)
                altTitles = mangaAttributesDto.altTitles
                    .mapNotNull { langMap ->
                        langMap
                            .filter { it.key == lang || it.key == "${mangaAttributesDto.originalLanguage}-ro" }
                            .takeIf { it.isNotEmpty() }
                    }
                    .flatMap { it.values }
                    .nullIfEmpty()

                val mangaRelationshipsDto = mangaDto.data.relationships
                cover = mangaRelationshipsDto
                    .firstOrNull { relationshipDto -> relationshipDto.type == MdConstants.Types.coverArt }
                    ?.attributes
                    ?.fileName
                    ?.let { coverFileName ->
                        MdUtil.cdnCoverUrl(mangaDto.data.id, "$coverFileName$coverQuality")
                    }

                val rawDesc = MdUtil.getFromLangMap(
                    langMap = mangaAttributesDto.description.asMdMap(),
                    currentLang = lang,
                    originalLanguage = mangaAttributesDto.originalLanguage,
                ).orEmpty()

                description = MdUtil.cleanDescription(rawDesc)

                authors = mangaRelationshipsDto.filter { relationshipDto ->
                    relationshipDto.type.equals(MdConstants.Types.author, true)
                }.mapNotNull { it.attributes?.name }.distinct()

                artists = mangaRelationshipsDto.filter { relationshipDto ->
                    relationshipDto.type.equals(MdConstants.Types.artist, true)
                }.mapNotNull { it.attributes?.name }.distinct()

                langFlag = mangaAttributesDto.originalLanguage
                val lastChapter = mangaAttributesDto.lastChapter?.toFloatOrNull()
                lastChapterNumber = lastChapter?.let { floor(it).toInt() }

                statistics?.rating?.let {
                    rating = it.bayesian?.toFloat()
                }

                mangaAttributesDto.links?.asMdMap<String>()?.let { links ->
                    links["al"]?.let { anilistId = it }
                    links["kt"]?.let { kitsuId = it }
                    links["mal"]?.let { myAnimeListId = it }
                    links["mu"]?.let { mangaUpdatesId = it }
                    links["ap"]?.let { animePlanetId = it }
                }

                val tempStatus = parseStatus(mangaAttributesDto.status)
                val publishedOrCancelled =
                    tempStatus == SManga.PUBLISHING_FINISHED || tempStatus == SManga.CANCELLED
                status = if (
                    mangaAttributesDto.lastChapter != null &&
                    publishedOrCancelled &&
                    mangaAttributesDto.lastChapter in simpleChapters
                ) {
                    SManga.COMPLETED
                } else {
                    tempStatus
                }

                // things that go with the genre tags but aren't actually genre
                val nonGenres = listOfNotNull(
                    mangaAttributesDto.publicationDemographic
                        ?.let {
                            RaisedTag(
                                "Demographic",
                                it.capitalize(Locale.US),
                                MangaDexSearchMetadata.TAG_TYPE_DEFAULT,
                            )
                        },
                    mangaAttributesDto.contentRating
                        ?.takeUnless { it == "safe" }
                        ?.let {
                            RaisedTag(
                                "Content Rating",
                                it.capitalize(Locale.US),
                                MangaDexSearchMetadata.TAG_TYPE_DEFAULT,
                            )
                        },
                )

                val genres = nonGenres + mangaAttributesDto.tags
                    .mapNotNull {
                        it.attributes.name[lang] ?: it.attributes.name["en"]
                    }
                    .map {
                        RaisedTag("Tags", it, MangaDexSearchMetadata.TAG_TYPE_DEFAULT)
                    }

                if (tags.isNotEmpty()) tags.clear()
                tags += genres
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "Error parsing MangaDex manga metadata" }
                throw e
            }
        }
    }

    private fun parseStatus(status: String?) = when (status) {
        "ongoing" -> SManga.ONGOING
        "completed" -> SManga.PUBLISHING_FINISHED
        "cancelled" -> SManga.CANCELLED
        "hiatus" -> SManga.ON_HIATUS
        else -> SManga.UNKNOWN
    }
}
