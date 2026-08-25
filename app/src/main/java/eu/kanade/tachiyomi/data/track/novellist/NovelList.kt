package eu.kanade.tachiyomi.data.track.novellist

import android.util.Base64
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.novellist.dto.NLNovel
import eu.kanade.tachiyomi.data.track.novellist.dto.NLUpdateRequest
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * NovelList (novellist.co), a light-novel directory.
 *
 * Ids are UUID strings, so the real identity lives in `remote_url` and `remote_id` carries only a
 * surrogate. The reference fork hashes the UUID into `remote_id` and hides the real one in a URL
 * fragment; nothing selects on `remote_id`, so the honest column is used instead.
 *
 * Every write carries the chapter count, because a body omitting it resets progress to zero.
 */
class NovelList(id: Long) : BaseTracker(id, "NovelList"), DeletableTracker, CookieLoginTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L

        // Score is 1..10 on the wire; index 0 is "unset", which the route takes as an omitted field.
        private val SCORE_LIST = listOf("-") + (1..10).map { it.toString() }

        private val SESSION_CHUNK = Regex("novellist(?:\\.(\\d+))?=([^;]+)")
        private val BASE64_BLOB = Regex("base64-([A-Za-z0-9+/=_-]+)")
        private val ACCESS_TOKEN = Regex("\"access_token\"\\s*:\\s*\"([^\"]+)\"")
    }

    private val interceptor by lazy { NovelListInterceptor(restoreToken()) }

    private val api by lazy {
        NovelListApi(interceptor, client) {
            trackPreferences.novelListApiUrl.get().trim().trimEnd('/')
                .ifBlank { NovelListApi.DEFAULT_API_URL }
        }
    }

    override fun getLogo(): Int = R.drawable.brand_novellist

    override val supportsNovels = true

    // Their catalogue holds novels only. It bills itself as a manhwa directory too, but manhwa are
    // links hanging off a novel row rather than entries: searching for one returns nothing.
    override val supportsManga = false

    // No route accepts a start or finish date.
    override val supportsReadingDates = false

    // On-hold is deliberately absent: the remote enum has no equivalent, and the reference fork's
    // mapping onto "planned" reads back as plan-to-read, a silent no-op the capability rule forbids.
    override fun getStatusList(): List<Long> = listOf(READING, COMPLETED, DROPPED, PLAN_TO_READ)

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        DROPPED -> MR.strings.dropped
        PLAN_TO_READ -> MR.strings.plan_to_read
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    // No reread state remotely, so a reread stays "Reading" rather than inventing one.
    override fun getRereadingStatus(): Long = READING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): List<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double = index.toDouble()

    override fun displayScore(track: DomainTrack): String =
        if (track.score <= 0.0) SCORE_LIST[0] else track.score.toInt().toString()

    override suspend fun search(query: String): List<TrackSearch> = searchNovel(query)

    override suspend fun searchNovel(query: String): List<TrackSearch> {
        query.trackerSearchId { it.takeIf(::isUuid) }
            ?.let { return listOf(api.getNovel(it).toTrackSearch()) }
        return api.searchNovels(query).map { it.toTrackSearch() }
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        track.status = if (hasReadChapters) READING else PLAN_TO_READ
        track.score = 0.0
        write(track)
        return track
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (didReadChapter && track.status != COMPLETED) {
            track.status = READING
        }
        write(track)
        return track
    }

    override suspend fun refresh(track: Track): Track {
        val entry = api.getReadingListEntry(track.uuid)
        track.status = entry.status.toLocalStatus()
        track.last_chapter_read = entry.chapterCount.toDouble()
        track.score = entry.rating ?: 0.0
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

    /**
     * Validates against the profile route before storing, and fills the username slot, without which
     * [isLoggedIn] stays false because it reads both the username and the password.
     */
    private suspend fun storeCredential(credential: String) {
        interceptor.newAuth(credential)
        try {
            val user = api.getCurrentUser()
            saveDisplayUsername(user.username)
            saveCredentials(user.username, credential)
        } catch (e: Throwable) {
            interceptor.newAuth(null)
            throw e
        }
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

    private val NLNovel.displayTitle: String
        get() = englishTitle?.ifBlank { null } ?: rawTitle?.ifBlank { null } ?: slug

    private fun String.toLocalStatus(): Long = when (this) {
        "COMPLETED" -> COMPLETED
        "DROPPED" -> DROPPED
        "PLANNED" -> PLAN_TO_READ
        else -> READING
    }

    private fun Long.toRemoteStatus(): String = when (this) {
        COMPLETED -> "COMPLETED"
        DROPPED -> "DROPPED"
        PLAN_TO_READ -> "PLANNED"
        else -> "IN_PROGRESS"
    }
}
