package exh.md.utils

import eu.kanade.tachiyomi.source.model.SManga
import exh.md.dto.MangaAttributesDto
import exh.md.dto.MangaDataDto
import kotlinx.serialization.json.Json
import org.jsoup.parser.Parser
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Phase 0 lands only the pure parsing/URL helpers. The source-discovery (getEnabledMangaDex),
// OAuth (saveOAuth/refreshTokenRequest), POST-body (encodeToBody) and i18n description helpers
// arrive with their phases (1, 3 and 2 respectively), when their dependencies exist.
class MdUtil {

    companion object {
        const val cdnUrl = "https://uploads.mangadex.org"
        const val baseUrl = "https://mangadex.org"
        const val chapterSuffix = "/chapter/"

        const val similarBaseApi = "https://api.similarmanga.com/similar/"

        const val mangaLimit = 20

        val jsonParser =
            Json {
                isLenient = true
                ignoreUnknownKeys = true
                allowSpecialFloatingPointValues = true
                useArrayPolymorphism = true
                prettyPrint = true
            }

        private const val scanlatorSeparator = " & "

        val markdownLinksRegex = "\\[([^]]+)]\\(([^)]+)\\)".toRegex()
        val markdownItalicBoldRegex = "\\*+\\s*([^*]*)\\s*\\*+".toRegex()
        val markdownItalicRegex = "_+\\s*([^_]*)\\s*_+".toRegex()

        fun buildMangaUrl(mangaUuid: String): String {
            return "/manga/$mangaUuid"
        }

        // Get the ID from the manga url
        fun getMangaId(url: String): String = url.trimEnd('/').substringAfterLast("/")

        fun getChapterId(url: String) = url.substringAfterLast("/")

        fun cleanDescription(string: String): String {
            return Parser.unescapeEntities(string, false)
                .substringBefore("\n---")
                .replace(markdownLinksRegex, "$1")
                .replace(markdownItalicBoldRegex, "$1")
                .replace(markdownItalicRegex, "$1")
                .trim()
        }

        fun getScanlatorString(scanlators: Set<String>): String {
            return scanlators.sorted().joinToString(scanlatorSeparator)
        }

        val dateFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss+SSS", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }

        fun parseDate(dateAsString: String): Long =
            dateFormatter.parse(dateAsString)?.time ?: 0

        fun createMangaEntry(json: MangaDataDto, lang: String): SManga {
            return SManga.create().apply {
                url = buildMangaUrl(json.id)
                title = getTitleFromManga(json.attributes, lang, true)
                thumbnail_url = json.relationships
                    .firstOrNull { relationshipDto -> relationshipDto.type == MdConstants.Types.coverArt }
                    ?.attributes
                    ?.fileName
                    ?.let { coverFileName ->
                        cdnCoverUrl(json.id, coverFileName)
                    }.orEmpty()
            }
        }

        fun getTitleFromManga(json: MangaAttributesDto, lang: String, preferExtensionLangTitle: Boolean): String {
            val titleMap = json.title.asMdMap<String>()
            val altTitles = json.altTitles
            val originalLang = json.originalLanguage

            titleMap[lang]?.let { return it }

            val mainTitle = titleMap.values.firstOrNull()
            val langAltTitle = altTitles.firstNotNullOfOrNull { it[lang] }
            val enTitle = findTitleInMaps("en", titleMap, altTitles)
            val originalLangTitle = findTitleInMaps("$originalLang-ro", titleMap, altTitles) ?: findTitleInMaps(
                originalLang,
                titleMap,
                altTitles,
            )

            val ordered = if (preferExtensionLangTitle) {
                listOf(langAltTitle, mainTitle, enTitle, originalLangTitle)
            } else {
                listOf(mainTitle, langAltTitle, enTitle, originalLangTitle)
            }

            return ordered.firstNotNullOfOrNull { it } ?: ""
        }

        fun getFromLangMap(langMap: Map<String, String>, currentLang: String, originalLanguage: String): String? {
            return langMap[currentLang]
                ?: langMap["en"]
                ?: if (originalLanguage == "ja") {
                    langMap["ja-ro"]
                        ?: langMap["jp-ro"]
                } else {
                    null
                }
        }

        fun findTitleInMaps(
            lang: String,
            titleMap: Map<String, String>,
            altTitleMaps: List<Map<String, String>>,
        ): String? {
            return titleMap[lang] ?: altTitleMaps.firstNotNullOfOrNull { it[lang] }
        }

        fun cdnCoverUrl(dexId: String, fileName: String): String {
            return "$cdnUrl/covers/$dexId/$fileName"
        }
    }
}
