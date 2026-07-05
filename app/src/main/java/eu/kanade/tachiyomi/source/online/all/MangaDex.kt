package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.LoginSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.md.dto.MangaDto
import exh.md.dto.StatisticsMangaDto
import exh.md.handlers.ApiMangaParser
import exh.md.handlers.FollowsHandler
import exh.md.handlers.MangaHandler
import exh.md.network.MangaDexLoginHelper
import exh.md.service.MangaDexAuthService
import exh.md.service.MangaDexService
import exh.md.utils.FollowStatus
import exh.md.utils.MdLang
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.source.DelegatedHttpSource
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.reflect.KClass

/**
 * MangaDex enhanced source. Wraps the installed MangaDex extension and enriches title details with
 * MangaDex metadata (namespaced tags, cross-tracker ids, rating), plus OAuth login and the MDList
 * tracker (follow-status and rating sync). Chapters, pages, browse and search delegate to the stock
 * extension, the same way [EightMuses] does. Follows-library sync, similar-manga and the external
 * aggregator page handlers arrive in later phases.
 */
class MangaDex(delegate: HttpSource, val context: Context) :
    DelegatedHttpSource(delegate),
    MetadataSource<MangaDexSearchMetadata, Triple<MangaDto, List<String>, StatisticsMangaDto?>>,
    LoginSource,
    NamespaceSource {

    override val lang: String = delegate.lang

    private val mdLang by lazy {
        MdLang.fromExt(lang) ?: MdLang.ENGLISH
    }

    private val trackPreferences: TrackPreferences by injectLazy()
    private val mdList: MdList by lazy { Injekt.get<TrackerManager>().mdList }

    private val loginHelper by lazy {
        MangaDexLoginHelper(client, headers, trackPreferences, mdList, mdList.interceptor)
    }

    // Authenticated client for the MDList tracker API. Reikai's DelegatedHttpSource.client is final
    // (= delegate.client), so the bearer interceptor can't ride the main client the way it does in
    // Komikku; build a dedicated client off the delegate's (extension UA, which the API needs).
    private val authClient: OkHttpClient by lazy {
        delegate.client.newBuilder()
            .addInterceptor(mdList.interceptor)
            .build()
    }

    private val mangadexService by lazy { MangaDexService(client, headers) }
    private val mangadexAuthService by lazy { MangaDexAuthService(authClient, headers) }
    private val apiMangaParser by lazy { ApiMangaParser(mdLang.lang) }
    private val mangaHandler by lazy { MangaHandler(mdLang.lang, mangadexService) }
    private val followsHandler by lazy { FollowsHandler(mangadexAuthService) }

    override val metaClass: KClass<MangaDexSearchMetadata> = MangaDexSearchMetadata::class

    override fun newMetaInstance() = MangaDexSearchMetadata()

    override suspend fun parseIntoMetadata(
        metadata: MangaDexSearchMetadata,
        input: Triple<MangaDto, List<String>, StatisticsMangaDto?>,
    ) {
        apiMangaParser.parseIntoMetadata(
            metadata,
            input.first,
            input.second,
            input.third,
            // Per-language prefs (cover quality, title-language preference, data-saver, blocked
            // groups, ...) get their SharedPreferences plumbing and settings UI in Phase 5. Phase 1
            // uses the defaults: full-quality cover, prefer the extension-language title.
            coverQuality = "",
            preferExtensionLangTitle = true,
        )
    }

    override suspend fun getMangaUpdate(
        manga: SManga,
        chapters: List<SChapter>,
        fetchDetails: Boolean,
        fetchChapters: Boolean,
    ): SMangaUpdate {
        val updatedManga = if (fetchDetails) {
            parseToManga(manga, mangaHandler.getMangaDetailsInput(manga))
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

    // LoginSource: browser OAuth (PKCE) only. login(username, password, ...) stays the default (no-op
    // false); login is driven from the tracking settings and the MangaDexLoginActivity callback.
    override val requiresLogin: Boolean = false

    override val twoFactorAuth = LoginSource.AuthSupport.NOT_SUPPORTED

    override fun isLogged(): Boolean = mdList.isLoggedIn

    override fun getUsername(): String = mdList.getUsername()

    override fun getPassword(): String = mdList.getPassword()

    override suspend fun login(authCode: String): Boolean = loginHelper.login(authCode)

    override suspend fun logout(): Boolean = loginHelper.logout()

    // MDList tracker round-trip (per-title follow status + rating), called by MdList.
    suspend fun fetchTrackingInfo(url: String): Track = followsHandler.fetchTrackingInfo(url)

    suspend fun updateFollowStatus(mangaId: String, followStatus: FollowStatus): Boolean =
        followsHandler.updateFollowStatus(mangaId, followStatus)

    suspend fun updateRating(track: Track): Boolean = followsHandler.updateRating(track)
}
