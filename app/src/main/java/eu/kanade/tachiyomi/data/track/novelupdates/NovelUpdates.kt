package eu.kanade.tachiyomi.data.track.novelupdates

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.serialization.json.Json
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * NovelUpdates (novelupdates.com), scraped because it publishes no API.
 *
 * It stores no reading position, so progress lives in the user's own note and every update is a
 * read-modify-write over their text. It also has no score and no reading dates, and no route
 * removes an entry, so unbinding is local only and [eu.kanade.tachiyomi.data.track.DeletableTracker]
 * is deliberately not implemented: the tracking sheet then hides the remove-from-service option.
 */
class NovelUpdates(id: Long) : BaseTracker(id, "NovelUpdates"), CookieLoginTracker {

    companion object {
        const val READING = 1L
        const val COMPLETED = 2L
        const val ON_HOLD = 3L
        const val DROPPED = 4L
        const val PLAN_TO_READ = 5L

        val STATUSES = listOf(READING, COMPLETED, ON_HOLD, DROPPED, PLAN_TO_READ)

        const val SESSION_COOKIE_PREFIX = "wordpress_logged_in"

        private val SESSION_COOKIE = Regex("(wordpress_logged_in_[^=]+=[^;]+)")
    }

    private val json: Json by injectLazy()

    private val api by lazy { NovelUpdatesApi(client) }

    override fun getLogo(): Int = R.drawable.brand_novelupdates

    override val supportsNovels = true

    // Their catalogue is web novels only; a manga has nothing here to bind to.
    override val supportsManga = false

    // No route accepts a score or a reading date. An empty score list hides the score row rather
    // than showing one that silently goes nowhere, which is what the reference fork does.
    override val supportsReadingDates = false

    override fun getScoreList(): List<String> = emptyList()

    override fun indexToScore(index: Int): Double = 0.0

    override fun displayScore(track: DomainTrack): String = ""

    override fun getStatusList(): List<Long> = STATUSES

    override fun getStatus(status: Long): StringResource? = when (status) {
        READING -> MR.strings.reading
        COMPLETED -> MR.strings.completed
        ON_HOLD -> MR.strings.on_hold
        DROPPED -> MR.strings.dropped
        PLAN_TO_READ -> MR.strings.plan_to_read
        else -> null
    }

    override fun getReadingStatus(): Long = READING

    override fun getRereadingStatus(): Long = READING

    override fun getCompletionStatus(): Long = COMPLETED

    override suspend fun search(query: String): List<TrackSearch> = searchNovel(query)

    override suspend fun searchNovel(query: String): List<TrackSearch> =
        api.search(query).map { it.toTrackSearch() }

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track {
        track.status = if (hasReadChapters) READING else PLAN_TO_READ
        // A search row does not always carry the numeric id, so it is resolved from the series page
        // before the first write rather than fabricated from the slug as the reference fork does.
        if (track.remote_id <= 0L) {
            val resolved = api.findNovelId(track.tracking_url)
                ?: throw IOException("Could not find this novel's id on NovelUpdates")
            track.remote_id = resolved.toLongOrNull()
                ?: throw IOException("NovelUpdates returned an unusable id")
        }
        push(track)
        return track
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (didReadChapter && track.status != COMPLETED) {
            track.status = READING
        }
        push(track)
        return track
    }

    override suspend fun refresh(track: Track): Track {
        val novelId = track.remote_id.toString()
        api.findListId(novelId)
            ?.let { mapping().statusFor(it) }
            ?.let { track.status = it }
        api.readNotes(novelId)
            ?.let { progressFrom(it.notes) }
            ?.let { track.last_chapter_read = it.toDouble() }
        return track
    }

    override suspend fun login(username: String, password: String) = storeCredential(password.trim())

    override val cookieLoginUrl: String = "${NovelUpdatesApi.BASE_URL}/login/"

    override val cookieDomain: String = NovelUpdatesApi.BASE_URL

    /**
     * The session cookie's name carries a per-install hash suffix, so it is matched by prefix. Only
     * the logged-in cookie is kept; the request itself is authenticated by the shared cookie jar,
     * which is the same store the sign-in window wrote to, so this is proof of sign-in rather than
     * something resent by hand.
     */
    override fun credentialFromCookies(cookies: String): String? =
        SESSION_COOKIE.find(cookies)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    override suspend fun loginWithCookie(credential: String) = storeCredential(credential)

    /** Reading the user's own lists proves the session works before the credential is stored. */
    private suspend fun storeCredential(credential: String) {
        if (api.readingLists().isEmpty()) {
            throw IOException("Signed in, but NovelUpdates returned no reading lists")
        }
        saveCredentials(name, credential)
    }

    /**
     * Status moves the entry between lists; progress is written into the note. The note is read back
     * first and left alone when it does not parse, so a bad response cannot blank what the user
     * wrote. Nothing here catches: a failed write must reach the caller rather than read as success.
     */
    private suspend fun push(track: Track) {
        val novelId = track.remote_id.toString()
        api.moveToList(novelId, mapping().listIdFor(track.status))

        val existing = api.readNotes(novelId) ?: return
        val updated = notesWithProgress(existing.notes, track.last_chapter_read.toInt())
        if (updated != existing.notes) {
            api.writeNotes(novelId, updated, existing.tags)
        }
    }

    private fun mapping(): NovelUpdatesListMapping =
        if (trackPreferences.novelUpdatesUseCustomListMapping.get()) {
            NovelUpdatesListMapping.from(trackPreferences.novelUpdatesCustomListMapping.get(), json)
        } else {
            NovelUpdatesListMapping.Default
        }

    suspend fun readingLists(): List<Pair<String, String>> = api.readingLists()

    private fun NovelUpdatesSeries.toTrackSearch(): TrackSearch = TrackSearch.create(this@NovelUpdates.id).also {
        it.remote_id = id?.toLongOrNull() ?: 0L
        it.title = title
        it.cover_url = coverUrl
        it.summary = summary
        it.tracking_url = seriesUrl
        it.publishing_status = publishingStatus
    }
}
