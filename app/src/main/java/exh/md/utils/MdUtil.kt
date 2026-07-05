package exh.md.utils

import eu.kanade.domain.source.service.SourcePreferences
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import eu.kanade.tachiyomi.network.POST
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.util.PkceUtil
import exh.md.dto.MangaAttributesDto
import exh.md.dto.MangaDataDto
import exh.source.getMainSource
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.parser.Parser
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

// Source-discovery (getEnabledMangaDex) and OAuth (saveOAuth/refreshTokenRequest) land here for the
// login + MDList tracker. The i18n description helpers (addAltTitleToDesc/addFinalChapterToDesc)
// arrive with the Phase 5 settings UI and its strings.
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

        inline fun <reified T> encodeToBody(body: T): RequestBody {
            return jsonParser.encodeToString(body)
                .toRequestBody("application/json".toMediaType())
        }

        fun saveOAuth(preferences: TrackPreferences, mdList: MdList, oAuth: MALOAuth?) {
            if (oAuth == null) {
                preferences.trackToken(mdList).delete()
            } else {
                preferences.trackToken(mdList).set(jsonParser.encodeToString(oAuth))
            }
        }

        fun loadOAuth(preferences: TrackPreferences, mdList: MdList): MALOAuth? {
            return try {
                jsonParser.decodeFromString<MALOAuth>(preferences.trackToken(mdList).get())
            } catch (_: Exception) {
                null
            }
        }

        private var codeVerifier: String? = null

        fun refreshTokenRequest(oauth: MALOAuth): Request {
            val formBody = FormBody.Builder()
                .add("client_id", MdConstants.Login.clientId)
                .add("grant_type", MdConstants.Login.refreshToken)
                .add("refresh_token", oauth.refreshToken)
                .add("code_verifier", getPkceChallengeCode())
                .add("redirect_uri", MdConstants.Login.redirectUri)
                .build()

            // The interceptor calls this itself, so it never reaches the point where the token is
            // added automatically. Add the Authorization header manually.
            val headers = Headers.Builder()
                .add("Authorization", "Bearer ${oauth.accessToken}")
                .build()

            return POST(MdApi.baseAuthUrl + MdApi.token, body = formBody, headers = headers)
        }

        fun getPkceChallengeCode(): String {
            return codeVerifier ?: PkceUtil.generateCodeVerifier().also { codeVerifier = it }
        }

        // Picks the MangaDex enhanced source that drives login and the MDList tracker. Phase 3 uses
        // the first enabled one; a preferred-language picker arrives with the Phase 5 settings hub.
        fun getEnabledMangaDex(
            sourcePreferences: SourcePreferences = Injekt.get(),
            sourceManager: SourceManager = Injekt.get(),
        ): MangaDex? {
            return getEnabledMangaDexs(sourcePreferences, sourceManager).firstOrNull()
        }

        fun getEnabledMangaDexs(
            preferences: SourcePreferences,
            sourceManager: SourceManager = Injekt.get(),
        ): List<MangaDex> {
            val languages = preferences.enabledLanguages.get()
            val disabledSourceIds = preferences.disabledSources.get()

            return sourceManager.getOnlineSources()
                .asSequence()
                .mapNotNull { it.getMainSource<MangaDex>() }
                .filter { it.lang in languages }
                .filterNot { it.id.toString() in disabledSourceIds }
                .toList()
        }
    }
}
