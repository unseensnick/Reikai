package eu.kanade.tachiyomi.data.track.ranobedb

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBSeries
import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBSeriesListEntry
import eu.kanade.tachiyomi.data.track.ranobedb.dto.RDBStaff
import tachiyomi.i18n.MR
import java.time.Instant
import java.time.ZoneId
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * RanobeDB (ranobedb.org), a light-novel database.
 *
 * Binds to a **series**, not a book: a series carries the volume count, the publication status and
 * the tag taxonomy, and its delete route needs no book-to-series lookup first.
 *
 * No route reads a user's own list entry back: the only GET under `/api/v0/user/` is `me`, and the
 * series detail route never passes the caller's id to its query. So [refresh] can only refresh
 * catalogue metadata, the local row stays authoritative for status and score, and a write cannot
 * preserve fields it does not send. See the plan doc for what that costs.
 */
class RanobeDb(id: Long) : BaseTracker(id, "RanobeDB"), DeletableTracker, CookieLoginTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L

        // Score is 1..10 on the wire; index 0 is "unset", which the API takes as null.
        private val SCORE_LIST = listOf("-") + (1..10).map { it.toString() }

        /**
         * Marks a stored credential as a session cookie rather than a token, so the interceptor
         * knows which header to send. A token is base32 without padding, so it can never contain
         * `=` and the two are impossible to confuse.
         */
        const val SESSION_COOKIE_PREFIX = "auth_session="

        private val SESSION_COOKIE = Regex("auth_session=([^;]+)")
    }

    private val interceptor by lazy { RanobeDbInterceptor(this) }

    private val api by lazy { RanobeDbApi(interceptor, client) }

    override fun getLogo(): Int = R.drawable.brand_ranobedb

    override val supportsNovels = true

    override val supportsReadingDates = true

    override fun getStatusList(): List<Long> =
        listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ)

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        DROPPED -> MR.strings.dropped
        PLAN_TO_READ -> MR.strings.plan_to_read
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    // RanobeDB has no reread state, so a reread stays "Reading" rather than inventing one.
    override fun getRereadingStatus(): Long = READING

    override fun getCompletionStatus(): Long = COMPLETED

    override fun getScoreList(): List<String> = SCORE_LIST

    override fun indexToScore(index: Int): Double = index.toDouble()

    // Anything at or below zero is unscored: the API's range starts at 1, and a search result's
    // score arrives as -1 until something sets it.
    override fun displayScore(track: DomainTrack): String =
        if (track.score <= 0.0) SCORE_LIST[0] else track.score.toInt().toString()

    override suspend fun search(query: String): List<TrackSearch> = searchNovel(query)

    override suspend fun searchNovel(query: String): List<TrackSearch> {
        query.trackerSearchId(String::toLongOrNull)?.let { seriesId ->
            return listOf(api.getSeries(seriesId).toTrackSearch())
        }
        return api.searchSeries(query).map { it.toTrackSearch() }
    }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        track.status = if (hasReadChapters) READING else PLAN_TO_READ
        // A search result carries score -1 as its "unset" marker, which would persist as a real
        // score and render as -1. Every other tracker zeroes it here for the same reason.
        track.score = 0.0
        api.updateSeriesListEntry(track.remote_id, track.toListEntry())
        return track
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        val statusBefore = track.status
        if (didReadChapter && track.status != COMPLETED) {
            track.status = READING
        }
        // Every write replaces list fields this API gives no way to read back first, so a push
        // driven by reading needs both the user's say-so and something actually worth saying.
        // Without the second test, finishing a chapter would rewrite the entry once per chapter.
        if (didReadChapter) {
            val statusMoved = track.status != statusBefore
            if (!statusMoved || !trackPreferences.ranobeDbSyncWhileReading.get()) return track
        }
        api.updateSeriesListEntry(track.remote_id, track.toListEntry())
        return track
    }

    override suspend fun refresh(track: Track): Track {
        val series = api.getSeries(track.remote_id)
        track.title = series.title
        // The detail route does not select c_num_books, only the list route does, so the count comes
        // from the books it does return. Never write a zero over a total a search already found.
        val total = series.volumeCount.takeIf { it > 0 } ?: series.books.size.toLong()
        if (total > 0) {
            track.total_chapters = total
        }
        return track
    }

    override suspend fun delete(track: DomainTrack) {
        api.deleteSeriesListEntry(track.remoteId)
    }

    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val series = api.getSeries(track.remoteId)
        val cover = series.books.minByOrNull { it.sortOrder }?.image?.filename
        return TrackMangaMetadata(
            remoteId = series.id,
            title = series.title,
            thumbnailUrl = cover?.let(RanobeDbApi::coverUrl),
            description = series.description?.ifBlank { null },
            authors = series.staff.namesOfRole("author"),
            artists = series.staff.namesOfRole("artist"),
            genres = series.tags.filter { it.ttype == "genre" }.map { it.name }.takeIf { it.isNotEmpty() },
        )
    }

    override suspend fun login(username: String, password: String) = login(password)

    suspend fun login(token: String) = storeCredential(token.trim())

    // RanobeDB's session cookie authenticates `/api/v0/user` just as a token does: the request hook
    // resolves the cookie into the caller before it looks for an Authorization header, and only
    // rejects a request that has neither. So both logins reach the same routes with the same bodies.
    override val cookieLoginUrl: String = "${RanobeDbApi.BASE_URL}/login"

    override val cookieDomain: String = RanobeDbApi.BASE_URL

    override fun credentialFromCookies(cookies: String): String? =
        SESSION_COOKIE.find(cookies)
            ?.groupValues
            ?.get(1)
            ?.takeIf { it.isNotBlank() }
            ?.let { SESSION_COOKIE_PREFIX + it }

    override suspend fun loginWithCookie(credential: String) = storeCredential(credential)

    /**
     * Both logins land here. The username slot is filled from `/user/me`, which proves the
     * credential works before it is stored and keeps [isLoggedIn] true, since that reads the
     * username and password slots and a token login leaves the username otherwise empty.
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

    // `this@RanobeDb.id` is the tracker id: a bare `id` here would resolve to the series' own.
    private fun RDBSeries.toTrackSearch(): TrackSearch = TrackSearch.create(this@RanobeDb.id).also {
        it.remote_id = id
        it.title = title
        it.total_chapters = volumeCount
        it.cover_url = book?.image?.filename?.let(RanobeDbApi::coverUrl).orEmpty()
        it.summary = description.orEmpty()
        it.tracking_url = RanobeDbApi.seriesUrl(id)
        it.publishing_status = publicationStatus.orEmpty()
    }

    private fun Track.toListEntry(): RDBSeriesListEntry = RDBSeriesListEntry(
        readingStatus = status.toRemoteStatus(),
        score = score.takeIf { it > 0.0 },
        // Progress is deliberately never sent. RanobeDB counts volumes while a source counts
        // chapters, and nothing converts between them, so pushing last_chapter_read would report
        // chapter 574 as 574 volumes of a 15-volume series. tsundoku omits it for the same reason.
        volumesRead = null,
        started = started_reading_date.toIsoDate(),
        finished = finished_reading_date.toIsoDate(),
        langs = emptyList(),
        formats = emptyList(),
        selectedCustLabels = emptyList(),
    )

    private fun Long.toRemoteStatus(): String = when (this) {
        COMPLETED -> "Finished"
        ON_HOLD -> "Stalled"
        DROPPED -> "Dropped"
        PLAN_TO_READ -> "Plan to read"
        else -> "Reading"
    }

    private fun Long.toIsoDate(): String? = takeIf { it > 0 }
        ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toString() }

    private fun List<RDBStaff>.namesOfRole(role: String): String? =
        filter { it.roleType.equals(role, ignoreCase = true) }
            .map { it.name }
            .distinct()
            .joinToString(", ")
            .ifEmpty { null }
}
