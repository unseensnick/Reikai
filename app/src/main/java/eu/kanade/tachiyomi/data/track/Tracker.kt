package eu.kanade.tachiyomi.data.track

import androidx.annotation.CallSuper
import androidx.annotation.DrawableRes
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.coroutines.flow.Flow
import okhttp3.OkHttpClient
import tachiyomi.domain.track.model.Track as DomainTrack

interface Tracker {

    val id: Long

    val name: String

    val client: OkHttpClient

    // Application and remote support for reading dates
    val supportsReadingDates: Boolean

    val supportsPrivateTracking: Boolean

    @DrawableRes
    fun getLogo(): Int

    fun getStatusList(): List<Long>

    fun getStatus(status: Long): StringResource?

    fun getReadingStatus(): Long

    fun getRereadingStatus(): Long

    fun getCompletionStatus(): Long

    fun getScoreList(): List<String>

    // TODO: Store all scores as 10 point in the future maybe?
    fun get10PointScore(track: DomainTrack): Double

    fun indexToScore(index: Int): Double

    fun displayScore(track: DomainTrack): String

    suspend fun update(track: Track, didReadChapter: Boolean = false): Track

    suspend fun bind(track: Track, hasReadChapters: Boolean = false): Track

    suspend fun search(query: String): List<TrackSearch>

    // RK --> novel-aware search (Active #8). Default delegates to the manga search so trackers outside
    // the novel-capable set degrade gracefully; the four light-novel trackers override it.
    suspend fun searchNovel(query: String): List<TrackSearch> = search(query)
    // RK <--

    suspend fun refresh(track: Track): Track

    // RK --> autofill entry metadata from a bound tracker (Fill from tracker). BaseTracker throws by
    // default; each supported service overrides it. Ported from Komikku, plus a genres field.
    suspend fun getMangaMetadata(track: DomainTrack): TrackMangaMetadata
    // RK <--

    suspend fun login(username: String, password: String)

    @CallSuper
    fun logout()

    val isLoggedIn: Boolean

    val isLoggedInFlow: Flow<Boolean>

    fun getUsername(): String

    fun getPassword(): String

    fun getDisplayUsername(): String

    fun saveDisplayUsername(displayName: String)

    fun saveCredentials(username: String, password: String)

    // TODO: move this to an interactor, and update all trackers based on common data
    suspend fun register(item: Track, mangaId: Long)

    suspend fun setRemoteStatus(track: Track, status: Long)

    suspend fun setRemoteLastChapterRead(track: Track, chapterNumber: Int)

    suspend fun setRemoteScore(track: Track, scoreString: String)

    suspend fun setRemoteStartDate(track: Track, epochMillis: Long)

    suspend fun setRemoteFinishDate(track: Track, epochMillis: Long)

    suspend fun setRemotePrivate(track: Track, private: Boolean)
}
