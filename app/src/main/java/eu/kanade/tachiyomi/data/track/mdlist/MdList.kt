package eu.kanade.tachiyomi.data.track.mdlist

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.BaseTracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.model.SManga
import exh.md.network.MangaDexAuthInterceptor
import exh.md.utils.FollowStatus
import exh.md.utils.MdUtil
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.i18n.MR
import tachiyomi.domain.track.model.Track as DomainTrack

class MdList(id: Long) : BaseTracker(id, "MDList") {

    companion object {
        private val SCORE_LIST = IntRange(0, 10)
            .map(Int::toString)
            .toImmutableList()
    }

    private val mdex by lazy { MdUtil.getEnabledMangaDex() }

    val interceptor = MangaDexAuthInterceptor(trackPreferences, this)

    override fun getLogo() = R.drawable.brand_mangadex

    override fun getStatusList(): List<Long> {
        return FollowStatus.entries.map { it.long }
    }

    override fun getStatus(status: Long): StringResource? = when (status) {
        0L -> MR.strings.md_follows_unfollowed
        1L -> MR.strings.reading
        2L -> MR.strings.completed
        3L -> MR.strings.on_hold
        4L -> MR.strings.plan_to_read
        5L -> MR.strings.dropped
        6L -> MR.strings.repeating
        else -> null
    }

    override fun getScoreList() = SCORE_LIST

    override fun displayScore(track: DomainTrack) = track.score.toInt().toString()

    override suspend fun update(track: Track, didReadChapter: Boolean): Track {
        return withIOContext {
            val mdex = mdex ?: throw MangaDexNotFoundException()

            val remoteTrack = mdex.fetchTrackingInfo(track.tracking_url)
            val followStatus = FollowStatus.fromLong(track.status)

            // allow follow status to update
            if (remoteTrack.status != followStatus.long) {
                if (mdex.updateFollowStatus(MdUtil.getMangaId(track.tracking_url), followStatus)) {
                    remoteTrack.status = followStatus.long
                } else {
                    track.status = remoteTrack.status
                }
            }

            if (remoteTrack.score != track.score) {
                mdex.updateRating(track)
            }

            track
        }
    }

    override fun getCompletionStatus(): Long = FollowStatus.COMPLETED.long

    override fun getReadingStatus(): Long = FollowStatus.READING.long

    override fun getRereadingStatus(): Long = FollowStatus.RE_READING.long

    override suspend fun bind(track: Track, hasReadChapters: Boolean): Track = update(
        refresh(track).also {
            if (it.status == FollowStatus.UNFOLLOWED.long) {
                it.status = if (hasReadChapters) {
                    FollowStatus.READING.long
                } else {
                    FollowStatus.PLAN_TO_READ.long
                }
            }
        },
    )

    override suspend fun refresh(track: Track): Track {
        return withIOContext {
            val mdex = mdex ?: throw MangaDexNotFoundException()
            val remoteTrack = mdex.fetchTrackingInfo(track.tracking_url)
            track.copyPersonalFrom(remoteTrack)
            track
        }
    }

    override suspend fun search(query: String): List<TrackSearch> {
        return withIOContext {
            val mdex = mdex ?: throw MangaDexNotFoundException()
            mdex.getSearchManga(1, query, mdex.getFilterList())
                .mangas
                .map {
                    // Reikai merged getMangaDetails into getMangaUpdate; enrich each hit so the bind
                    // sheet gets the full cover + description, matching Komikku's getMangaDetails path.
                    toTrackSearch(mdex.getMangaUpdate(it, emptyList(), fetchDetails = true, fetchChapters = false).manga)
                }
                .distinct()
        }
    }

    private fun toTrackSearch(mangaInfo: SManga): TrackSearch = TrackSearch.create(id).apply {
        tracking_url = MdUtil.baseUrl + mangaInfo.url
        title = mangaInfo.title
        cover_url = mangaInfo.thumbnail_url.orEmpty()
        summary = mangaInfo.description.orEmpty()
    }

    override suspend fun login(username: String, password: String): Unit = throw Exception("not used")

    override fun logout() {
        super.logout()
        trackPreferences.trackToken(this).delete()
    }

    override val isLoggedIn: Boolean
        get() = trackPreferences.trackToken(this).get().isNotEmpty()

    override val isLoggedInFlow: Flow<Boolean> by lazy {
        trackPreferences.trackToken(this).changes().map { it.isNotEmpty() }
    }

    class MangaDexNotFoundException : Exception("Mangadex not enabled")
}
