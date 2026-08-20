package eu.kanade.tachiyomi.data.track

import android.app.Application
import android.content.Context
import androidx.annotation.CallSuper
import eu.kanade.domain.track.interactor.AddTracks
import eu.kanade.domain.track.model.toDomainTrack
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import logcat.LogPriority
import mihon.app.di.appGraph
import okhttp3.OkHttpClient
import reikai.domain.track.TrackFieldMutations
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.interactor.InsertTrack
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import tachiyomi.domain.track.model.Track as DomainTrack

abstract class BaseTracker(
    override val id: Long,
    override val name: String,
) : Tracker {

    protected val appGraph get() = Injekt.get<Context>().appGraph

    val trackPreferences: TrackPreferences by lazy { appGraph.trackPreferences }
    val networkService: NetworkHelper by lazy { appGraph.networkHelper }

    private val context: Context by lazy { appGraph.context }
    private val addTracks: AddTracks by lazy { appGraph.addTracks }
    private val insertTrack: InsertTrack by lazy { appGraph.insertTrack }

    override val client: OkHttpClient
        get() = networkService.client

    // Application and remote support for reading dates
    override val supportsReadingDates: Boolean = false

    override val supportsPrivateTracking: Boolean = false

    // RK --> novel search capability; overridden true by the novel-capable trackers
    override val supportsNovels: Boolean = false

    /**
     * The `id:` search prefix, defined once so a tracker's manga and novel searches cannot answer it
     * differently. [parse] turns the rest of the query into that service's own id; returning null
     * falls through to an ordinary title search, which is what an unparseable id does.
     */
    protected fun <T : Any> String.trackerSearchId(parse: (String) -> T?): T? =
        takeIf { it.startsWith(SEARCH_ID_PREFIX) }
            ?.substringAfter(SEARCH_ID_PREFIX)
            ?.trim()
            ?.let(parse)
    // RK <--

    // TODO: Store all scores as 10 point in the future maybe?
    override fun get10PointScore(track: DomainTrack): Double {
        return track.score
    }

    override fun indexToScore(index: Int): Double {
        return index.toDouble()
    }

    @CallSuper
    override fun logout() {
        trackPreferences.setCredentials(this, "", "")
    }

    override val isLoggedIn: Boolean
        get() = getUsername().isNotEmpty() &&
            getPassword().isNotEmpty()

    override val isLoggedInFlow: Flow<Boolean> by lazy {
        combine(
            trackPreferences.trackUsername(this).changes(),
            trackPreferences.trackPassword(this).changes(),
        ) { username, password ->
            username.isNotEmpty() && password.isNotEmpty()
        }
    }

    override fun getUsername() = trackPreferences.trackUsername(this).get()

    override fun getDisplayUsername(): String = trackPreferences.trackDisplayUsername(this).get()

    override fun saveDisplayUsername(displayName: String) = trackPreferences.trackDisplayUsername(this).set(displayName)

    override fun getPassword() = trackPreferences.trackPassword(this).get()

    override fun saveCredentials(username: String, password: String) {
        trackPreferences.setCredentials(this, username, password)
    }

    override suspend fun register(item: Track, mangaId: Long) {
        item.manga_id = mangaId
        try {
            addTracks.bind(this, item, mangaId)
        } catch (e: Throwable) {
            withUIContext { context.toast(e.message) }
        }
    }

    override suspend fun setRemoteStatus(track: Track, status: Long) {
        // RK --> field transition shared with the novel writer, so novels inherit upstream changes
        TrackFieldMutations.applyStatus(this, track, status)
        // RK <--
        updateRemote(track)
    }

    override suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int) {
        // RK --> field transition shared with the novel writer, so novels inherit upstream changes
        TrackFieldMutations.applyLastChapterRead(this, track, chapterNumber)
        // RK <--
        updateRemote(track)
    }

    override suspend fun setRemoteScore(track: Track, scoreString: String) {
        // RK --> field transition shared with the novel writer, so novels inherit upstream changes
        TrackFieldMutations.applyScore(this, track, scoreString)
        // RK <--
        updateRemote(track)
    }

    override suspend fun setRemoteStartDate(track: Track, epochMillis: Long) {
        track.started_reading_date = epochMillis
        updateRemote(track)
    }

    override suspend fun setRemoteFinishDate(track: Track, epochMillis: Long) {
        track.finished_reading_date = epochMillis
        updateRemote(track)
    }

    override suspend fun setRemotePrivate(track: Track, private: Boolean) {
        track.private = private
        updateRemote(track)
    }

    // RK --> throwing default; supported trackers override to autofill entry metadata.
    override suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata {
        throw NotImplementedError("Not implemented.")
    }
    // RK <--

    private suspend fun updateRemote(track: Track): Unit = withIOContext {
        try {
            update(track)
            track.toDomainTrack(idRequired = false)?.let {
                insertTrack.await(it)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to update remote track data id=$id" }
            withUIContext { context.toast(e.message) }
        }
    }
}

// RK: the prefix every tracker's id search is spelled with.
private const val SEARCH_ID_PREFIX = "id:"
