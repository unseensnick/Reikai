package eu.kanade.tachiyomi.source.online.all

import android.content.Context
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.SMangaUpdate
import eu.kanade.tachiyomi.source.online.FollowsSource
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.source.online.LoginSource
import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.NamespaceSource
import eu.kanade.tachiyomi.source.online.RandomMangaSource
import exh.md.dto.MangaDataDto
import exh.md.dto.MangaDto
import exh.md.dto.StatisticsMangaDto
import exh.md.handlers.ApiMangaParser
import exh.md.handlers.FollowsHandler
import exh.md.handlers.MangaHandler
import exh.md.network.MangaDexLoginHelper
import exh.md.service.MangaDexAuthService
import exh.md.service.MangaDexService
import exh.md.utils.FollowStatus
import exh.md.utils.MdConstants
import exh.md.utils.MdLang
import exh.md.utils.MdUtil
import exh.md.utils.asMdMap
import exh.metadata.metadata.MangaDexSearchMetadata
import exh.source.DelegatedHttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import kotlin.math.round
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
    FollowsSource,
    RandomMangaSource,
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
    private val followsHandler by lazy { FollowsHandler(mdLang.lang, mangadexAuthService) }

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

    // FollowsSource: the signed-in user's follow list, paged for the follows browse screen and
    // walked in full for the bulk "Sync Follows to Library" action.
    override suspend fun fetchFollows(page: Int): MangasPage = followsHandler.fetchFollows(page)

    override suspend fun fetchAllFollows(): List<Pair<SManga, MangaDexSearchMetadata>> =
        followsHandler.fetchAllFollows()

    // RandomMangaSource: a random title id for the Browse "Random" button.
    override suspend fun fetchRandomMangaUrl(): String = mangaHandler.fetchRandomMangaId()

    // MDList tracker round-trip (per-title follow status + rating), called by MdList.
    suspend fun fetchTrackingInfo(url: String): Track = followsHandler.fetchTrackingInfo(url)

    suspend fun updateFollowStatus(mangaId: String, followStatus: FollowStatus): Boolean =
        followsHandler.updateFollowStatus(mangaId, followStatus)

    suspend fun updateRating(track: Track): Boolean = followsHandler.updateRating(track)

    // MDList "Fill from tracker" metadata: reuse the normal details-parse pipeline (getMangaUpdate ->
    // parseToManga) on a bare SManga carrying the tracking URL, and hand the parsed SManga to MdList.
    suspend fun getMangaMetadata(trackingUrl: String): SManga {
        // title is a non-null lateinit the parse pipeline reads via copy(); seed it so a bare input
        // doesn't crash. parseIntoMetadata overwrites it with the real title.
        val manga = SManga.create().apply {
            url = trackingUrl
            title = ""
        }
        return getMangaUpdate(manga, emptyList(), fetchDetails = true, fetchChapters = false).manga
    }

    // MDList tracker search. One search call for the hits, then two batched calls (full details +
    // ratings) so the bind sheet is as rich as the other trackers without N per-result round-trips.
    suspend fun searchTracker(query: String): List<TrackSearch> {
        val ids = getSearchManga(1, query, getFilterList()).mangas
            .map { MdUtil.getMangaId(it.url) }
            .distinct()
            // MangaDex caps ids[] batch calls (viewMangas / mangasRating) at 100.
            .take(100)
        if (ids.isEmpty()) return emptyList()

        return coroutineScope {
            val details = async { mangadexService.viewMangas(ids) }
            val ratings = async {
                runCatching { mangadexService.mangasRating(*ids.toTypedArray()).statistics }.getOrElse { emptyMap() }
            }
            val byId = details.await().data.associateBy { it.id }
            val ratingById = ratings.await()
            // Preserve the search's relevance order.
            ids.mapNotNull { id -> byId[id]?.let { toTrackSearch(it, ratingById[id]?.rating?.bayesian) } }
        }
    }

    private fun toTrackSearch(data: MangaDataDto, rating: Double?): TrackSearch = TrackSearch.create(mdList.id).apply {
        val attrs = data.attributes
        // MangaDex ids are UUIDs; hash to a Long so distinct results don't collapse (TrackSearch
        // dedupes on remote_id).
        remote_id = data.id.hashCode().toLong()
        title = MdUtil.getTitleFromManga(attrs, mdLang.lang, true)
        tracking_url = MdUtil.baseUrl + MdUtil.buildMangaUrl(data.id)
        cover_url = data.relationships
            .firstOrNull { it.type == MdConstants.Types.coverArt }
            ?.attributes?.fileName
            ?.let { MdUtil.cdnCoverUrl(data.id, it) }
            .orEmpty()
        summary = MdUtil.getFromLangMap(attrs.description.asMdMap<String>(), mdLang.lang, attrs.originalLanguage)
            ?.let { MdUtil.cleanDescription(it) }
            .orEmpty()
        authors = data.relationships.filter { it.type == MdConstants.Types.author }.mapNotNull { it.attributes?.name }
        artists = data.relationships.filter { it.type == MdConstants.Types.artist }.mapNotNull { it.attributes?.name }
        publishing_status = attrs.status.orEmpty()
        publishing_type = data.type
        start_date = attrs.year?.toString().orEmpty()
        // MangaDex's bayesian rating has many decimals; show it to 2 (e.g. 9.19).
        score = rating?.let { round(it * 100) / 100 } ?: -1.0
    }
}
