package eu.kanade.tachiyomi.data.track.novelupdates

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.CookieLoginTracker
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import logcat.LogPriority
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.track.UnreadPushTracker
import reikai.domain.track.autobind.AutoBindEntry
import reikai.domain.track.autobind.AutoBindTracker
import reikai.domain.track.site.OwnedSites
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.service.ChapterRecognition
import tachiyomi.i18n.MR
import uy.kohesive.injekt.injectLazy
import java.io.IOException
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * NovelUpdates (novelupdates.com), scraped because it publishes no API.
 *
 * It stores no reading position, so progress lives in the user's own note and every update is a
 * read-modify-write over their text. It has no score and no reading dates either, so both rows stay
 * hidden rather than doing nothing.
 */
class NovelUpdates(id: Long) :
    BaseTracker(id, "NovelUpdates"),
    DeletableTracker,
    CookieLoginTracker,
    AutoBindTracker,
    UnreadPushTracker {

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
        // A search row does not always carry the numeric id, so it is resolved from the series page
        // before the first write rather than fabricated from the slug as the reference fork does.
        if (track.remote_id <= 0L) {
            val resolved = api.findNovelId(track.tracking_url)
                ?: throw IOException("Could not find this novel's id on NovelUpdates")
            track.remote_id = resolved.toLongOrNull()
                ?: throw IOException("NovelUpdates returned an unusable id")
        }
        val novelId = track.remote_id.toString()
        val listId = api.findListId(novelId)
        val siteStatus = listId?.let { mapping().statusFor(it) }
        when (val plan = bindOnSite(listId != null, siteStatus, hasReadChapters)) {
            is BindOnSite.File -> {
                track.status = plan.status
                push(track)
            }
            is BindOnSite.Keep -> {
                // The bind's backfill still moves the progress forward when the app has read further.
                siteStatus?.let { track.status = it }
                api.readNotes(novelId)?.let { progressFrom(it.notes) }?.let { track.last_chapter_read = it.toDouble() }
                plan.moveTo?.let {
                    track.status = it
                    api.moveToList(novelId, mapping().listIdFor(it))
                }
            }
        }
        return track
    }

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        if (didReadChapter && track.status != COMPLETED) {
            track.status = READING
        }
        push(track, readChapter = didReadChapter)
        return track
    }

    override suspend fun pushUnread(track: Track, unread: List<NovelChapter>): Track? {
        if (!trackPreferences.novelUpdatesUnreadPush.get()) return null
        val novelId = track.remote_id.toString()
        val target = unreadTarget(unread) { it.chapterNumber } ?: return null
        val onSite = api.readNotes(novelId)?.let { progressFrom(it.notes) }
        // Called after the unread is written, so this is what is still read.
        val chapters = groupChapters(track.manga_id)
        val stillRead = chapters
            .filter { it.read && it.chapterNumber > 0 }
            .maxOfOrNull { it.chapterNumber }
        val progress = progressAfterUnread(target.chapterNumber, stillRead, onSite) ?: return null
        // With nothing still read the bookmark stays: the site has no way to move it to "none".
        if (progress > 0) bookmarkRelease(track, novelId, progress, readReleaseIds(chapters, progress))
        track.last_chapter_read = progress
        push(track)
        return track
    }

    override val tracker: Tracker get() = this

    // Any novel can be searched for here; only the site's own sources skip the search.
    override val offeredOnlyWhenAccepted = false

    // The site's own sources: its extension, and the LNReader plugin reading the same pages.
    override fun accepts(entry: AutoBindEntry): Boolean =
        entry is AutoBindEntry.Novel && OwnedSites.ownerOf(entry.source)?.trackerId == id

    override suspend fun match(entry: AutoBindEntry): TrackSearch? {
        val novel = entry as? AutoBindEntry.Novel ?: return null
        val link = novel.source.resolveUrl(novel.novel.url, isNovel = true) ?: novel.novel.url
        val seriesUrl = NovelUpdatesApi.seriesUrl(seriesSlugOf(link) ?: return null)
        val details = api.details(seriesUrl)
        // The post id is left for bind, which resolves it from the series page.
        return TrackSearch.create(id).also {
            it.tracking_url = seriesUrl
            it.title = details.title ?: novel.novel.title
            it.cover_url = details.coverUrl.orEmpty()
            it.summary = details.description.orEmpty()
        }
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

    override suspend fun delete(track: DomainTrack) = api.removeFromList(track.remoteId.toString())

    /**
     * Genres only, not the sibling tag list: a popular series carries eighty or more tags against a
     * handful of genres, and they share one field, so tags would bury what describes the work.
     */
    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        val details = api.details(track.remoteUrl)
        return TrackMangaMetadata(
            remoteId = track.remoteId,
            title = details.title,
            thumbnailUrl = details.coverUrl,
            description = details.description,
            authors = details.authors.joinToString().ifBlank { null },
            artists = details.artists.joinToString().ifBlank { null },
            genres = details.genres.takeIf { it.isNotEmpty() },
        )
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

    /**
     * Reading the account page proves the session works before the credential is stored, and gives
     * the name for the tracker row. Falling back to the service name would put a label there that is
     * not the user's, which reads worse than the blank the widget leaves when the name is missing.
     */
    private suspend fun storeCredential(credential: String) {
        val username = signedInAccount().username.orEmpty()
        saveDisplayUsername(username)
        saveCredentials(username.ifBlank { name }, credential)
    }

    override suspend fun updateUserConfig() {
        saveDisplayUsername(signedInAccount().username.orEmpty())
    }

    // A logged-out page still loads, so an account with no reading lists is the only sign of a dead session.
    private suspend fun signedInAccount(): NovelUpdatesAccount {
        val account = api.account()
        if (account.lists.isEmpty()) {
            throw IOException("NovelUpdates shows no reading lists: sign in again")
        }
        return account
    }

    /**
     * Status moves the entry between lists; progress is written into the note. The note is read back
     * first and left alone when it does not parse, so a bad response cannot blank what the user
     * wrote. Nothing here catches: a failed write must reach the caller rather than read as success.
     * A [readChapter] also moves the site's bookmark to its release, and never moves the site back
     * unless the user allows it.
     */
    private suspend fun push(track: Track, readChapter: Boolean = false) {
        val novelId = track.remote_id.toString()
        api.moveToList(novelId, mapping().listIdFor(track.status))

        val existing = api.readNotes(novelId) ?: return
        val onSite = progressFrom(existing.notes)
        val neverBackwards = trackPreferences.novelUpdatesNeverBackwards.get()
        if (holdsBack(readChapter, neverBackwards, track.last_chapter_read, onSite)) {
            // The caller stores what this returns, so the app keeps the site's later chapter too.
            track.last_chapter_read = onSite!!.toDouble()
            return
        }
        if (readChapter) {
            val readIds = readReleaseIds(groupChapters(track.manga_id), track.last_chapter_read)
            bookmarkRelease(track, novelId, track.last_chapter_read, readIds)
        }
        val updated = notesWithProgress(existing.notes, track.last_chapter_read.toInt())
        if (updated != existing.notes) {
            api.writeNotes(novelId, updated, existing.tags)
        }
    }

    /**
     * Moves the site's bookmark to the release for chapter [number]. One that cannot be resolved or
     * written is skipped rather than failing the push, since the note still carries the progress.
     */
    private suspend fun bookmarkRelease(
        track: Track,
        novelId: String,
        number: Double,
        readIds: Set<String>,
    ) {
        try {
            val releaseId = pickRelease(number, readIds, { api.releases(novelId) }) {
                ChapterRecognition.parseChapterNumber(track.title, it)
            } ?: return
            api.bookmarkRelease(novelId, releaseId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.WARN, e) { "Could not mark a NovelUpdates release" }
        }
    }

    /** The site release ids of the read chapters numbered [number]. */
    private fun readReleaseIds(chapters: List<NovelChapter>, number: Double): Set<String> =
        chapters.filter { it.read && it.chapterNumber == number }.mapNotNullTo(HashSet()) { releaseIdOf(it.url) }

    /** The novel's chapters and those of the sources merged with it, since a track spans the group. */
    private suspend fun groupChapters(novelId: Long): List<NovelChapter> =
        appGraph.novelMergeManager.computeRelatedIds(novelId)
            .flatMap { appGraph.novelChapterRepository.getByNovelId(it) }

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
