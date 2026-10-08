package eu.kanade.tachiyomi.data.track.novellist

import android.util.Base64
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.novellist.dto.NLNovel
import eu.kanade.tachiyomi.data.track.novellist.dto.NLReadingListEntry
import eu.kanade.tachiyomi.data.track.novellist.dto.NLUpdateRequest
import eu.kanade.tachiyomi.network.HttpException
import reikai.data.track.MetadataAccess
import reikai.data.track.NovelStatusTracker
import reikai.data.track.NovelTrackerStatuses
import reikai.data.track.TenPointScore
import reikai.data.track.storeCheckedCredential
import reikai.domain.track.TrackFieldMutations
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * NovelList (novellist.co), a light-novel directory.
 *
 * Ids are UUID strings, so the UUID rides in the tracking URL's fragment and `remote_id` carries only
 * a surrogate (see NovelListIdentity.kt).
 *
 * Every write carries the chapter count, because a body omitting it resets progress to zero.
 */
class NovelList(id: Long) :
    BaseTracker(id, "NovelList"),
    DeletableTracker,
    CookieLoginTracker,
    NovelStatusTracker {

    companion object {
        const val READING = NovelTrackerStatuses.READING
        const val COMPLETED = NovelTrackerStatuses.COMPLETED
        const val DROPPED = NovelTrackerStatuses.DROPPED
        const val PLAN_TO_READ = NovelTrackerStatuses.PLAN_TO_READ

        private val SESSION_CHUNK = Regex("novellist(?:\\.(\\d+))?=([^;]+)")
        private val BASE64_BLOB = Regex("base64-([A-Za-z0-9+/=_-]+)")
        private val ACCESS_TOKEN = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
    }

    private val interceptor by lazy { NovelListInterceptor(restoreToken()) }

    private val api by lazy {
        NovelListApi(interceptor, client) { NovelListApi.novelListApiUrl(trackPreferences.novelListApiUrl.get()) }
    }

    override fun getLogo(): Int = R.drawable.brand_novellist

    override val supportsNovels = true

    // Metadata comes from the public catalogue, no login needed.
    override val metadataAccess = MetadataAccess.Public

    // Their catalogue holds novels only. It bills itself as a manhwa directory too, but manhwa are
    // links hanging off a novel row rather than entries: searching for one returns nothing.
    override val supportsManga = false

    // No route accepts a start or finish date.
    override val supportsReadingDates = false

    // On-hold is deliberately absent: the remote enum has no equivalent, and the reference fork's
    // mapping onto "planned" reads back as plan-to-read, a silent no-op the capability rule forbids.
    override fun getStatusList(): List<Long> = listOf(READING, COMPLETED, DROPPED, PLAN_TO_READ)

    // Index 0, unset, goes out as an omitted field, which the route takes as no score.
    override fun getScoreList(): List<String> = TenPointScore.list

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun displayScore(track: DomainTrack): String = TenPointScore.display(track.score)

    override suspend fun search(query: String): List<TrackSearch> = searchNovel(query)

    override suspend fun searchNovel(query: String): List<TrackSearch> {
        query.trackerSearchId { it.takeIf(::isUuid) }
            ?.let { return listOf(api.getNovel(it).toTrackSearch()) }
        return api.searchNovels(query).map { it.toTrackSearch() }
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        val entry = readingListEntryOrNull(track.uuid)
        if (entry == null) {
            track.status = statusOnBind(null, hasReadChapters)
            track.score = 0.0
            write(track)
            return track
        }
        // The site's progress and score are adopted; the bind's backfill still moves progress forward
        // when the app has read further.
        entry.copyInto(track)
        val status = statusOnBind(track.status, hasReadChapters)
        if (status != track.status) {
            track.status = status
            write(track)
        }
        return track
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (didReadChapter) applyReadPush(track)
        write(track)
        return track
    }

    override suspend fun refresh(track: Track): Track {
        refreshTrack(track, api.getReadingListEntry(track.uuid), api.getNovel(track.uuid).chapterCount)
        return track
    }

    override suspend fun delete(track: DomainTrack) = api.deleteReadingListEntry(track.uuid)

    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val novel = api.getNovel(track.uuid)
        return TrackMangaMetadata(
            remoteId = track.remoteId,
            title = novel.displayTitle,
            thumbnailUrl = novel.coverImageLink,
            description = novel.description?.ifBlank { null },
            authors = novel.author?.name,
            artists = null,
            genres = novel.labels
                ?.filter { it.type == "GENRE" }
                ?.map { it.name }
                ?.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun login(username: String, password: String) = login(password)

    suspend fun login(token: String) = storeCredential(token.trim())

    override val cookieLoginUrl: String = "${NovelListApi.BASE_URL}/sign-in"

    override val cookieDomain: String = NovelListApi.BASE_URL

    /**
     * The sign-in cookie is a base64 session blob, split across `novellist.0`, `novellist.1` and so
     * on once it outgrows one cookie, so chunks rejoin in numeric order before the blob is decoded
     * as URL-safe base64 and the JWT read out of it. The blob also carries a refresh token, dropped
     * on purpose: a stale access token costs one sign-in, a leaked refresh token is a standing key.
     */
    override fun credentialFromCookies(cookies: String): String? {
        val joined = SESSION_CHUNK.findAll(cookies)
            .sortedBy { it.groupValues[1].toIntOrNull() ?: 0 }
            .joinToString("") { it.groupValues[2] }
        val blob = BASE64_BLOB.find(joined)?.groupValues?.get(1) ?: return null
        val decoded = runCatching {
            String(Base64.decode(blob, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        }.getOrNull() ?: return null
        return ACCESS_TOKEN.find(decoded)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    override suspend fun loginWithCookie(credential: String) = storeCredential(credential)

    // Validated against the profile route before it is stored.
    private suspend fun storeCredential(credential: String) =
        storeCheckedCredential(credential, interceptor::newAuth) { api.getCurrentUser().username }

    override suspend fun updateUserConfig() {
        saveDisplayUsername(api.getCurrentUser().username)
    }

    override fun logout() {
        super.logout()
        interceptor.newAuth(null)
    }

    fun restoreToken(): String? = trackPreferences.trackPassword(this).get().ifBlank { null }

    /**
     * The chapter count goes on every write, never conditionally: the route resets progress to zero
     * for any body without it, so a status or score edit alone would wipe it. Measured live.
     */
    private suspend fun write(track: Track) = api.updateReadingListEntry(
        track.uuid,
        NLUpdateRequest(
            chapterCount = track.last_chapter_read.toLong(),
            status = track.status.toRemoteStatus(),
            rating = track.score.takeIf { it > 0.0 },
        ),
    )

    // `this@NovelList.id` is the tracker id: a bare `id` would resolve to the novel's own.
    //
    // total_chapters carries the catalogue count, which counts chapters here exactly as the source
    // does, so unlike RanobeDB's volume count it is safe to store beside last_chapter_read.
    private fun NLNovel.toTrackSearch(): TrackSearch = TrackSearch.create(this@NovelList.id).also {
        it.remote_id = surrogateIdOf(id)
        it.title = displayTitle
        it.cover_url = coverImageLink.orEmpty()
        it.summary = description.orEmpty()
        it.tracking_url = novelListTrackingUrl(slug, id)
        it.total_chapters = chapterCount ?: 0
        it.publishing_status = status.orEmpty()
    }

    // A 404 is read as "not on the list". Any other failure stays one, because falling through to the
    // blind write is what used to overwrite the user's entry.
    private suspend fun readingListEntryOrNull(uuid: String): NLReadingListEntry? = try {
        api.getReadingListEntry(uuid)
    } catch (e: HttpException) {
        if (e.code != 404) throw e
        null
    }

    private val NLNovel.displayTitle: String
        get() = englishTitle?.ifBlank { null } ?: rawTitle?.ifBlank { null } ?: slug

    private fun Long.toRemoteStatus(): String = when (this) {
        COMPLETED -> "COMPLETED"
        DROPPED -> "DROPPED"
        PLAN_TO_READ -> "PLANNED"
        else -> "IN_PROGRESS"
    }
}

/**
 * MyAnimeList's bind rule over NovelList's statuses. A novel not on the list ([siteStatus] null) is
 * filed by whether anything was read. One already there keeps its status unless chapters were read and
 * it is not Completed, which moves it to Reading; with no remote reread state, Dropped reopens.
 */
internal fun statusOnBind(siteStatus: Long?, hasReadChapters: Boolean): Long = when {
    siteStatus == null -> NovelTrackerStatuses.unlisted(hasReadChapters)
    hasReadChapters && siteStatus != NovelList.COMPLETED -> NovelList.READING
    else -> siteStatus
}

/** A read files the novel under Reading, or Completed once it reaches the total, unless already Completed. */
internal fun NovelList.applyReadPush(track: Track) {
    if (track.status == NovelList.COMPLETED) return
    track.status = NovelList.READING
    TrackFieldMutations.completeAtTotal(this, track)
}

/**
 * The total follows the catalogue on every refresh, because an ongoing novel keeps growing: a total
 * frozen at bind let Completed (TrackFieldMutations.applyStatus) push progress back down to it.
 */
internal fun refreshTrack(track: Track, entry: NLReadingListEntry, catalogueCount: Long?) {
    entry.copyInto(track)
    catalogueCount?.let { track.total_chapters = it }
}

private fun NLReadingListEntry.copyInto(track: Track) {
    track.status = status.toLocalStatus()
    track.last_chapter_read = chapterCount.toDouble()
    track.score = rating ?: 0.0
}

private fun String.toLocalStatus(): Long = when (this) {
    "COMPLETED" -> NovelList.COMPLETED
    "DROPPED" -> NovelList.DROPPED
    "PLANNED" -> NovelList.PLAN_TO_READ
    else -> NovelList.READING
}
