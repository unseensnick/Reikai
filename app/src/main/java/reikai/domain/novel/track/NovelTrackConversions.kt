package reikai.domain.novel.track

import eu.kanade.domain.track.model.toDomainTrack
import reikai.domain.novel.model.NovelTrack
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * Bridge between [NovelTrack] and the mutable [DbTrack] the tracker services operate on, mirroring the
 * manga `toDbTrack` / `toDomainTrack` (eu.kanade.domain.track.model.Track). The remote APIs only read
 * `remote_id`, so the `manga_id` slot carries the `novelId` purely to round-trip it; novel persistence
 * never touches `manga_sync`.
 */
fun NovelTrack.toDbTrack(): DbTrack = DbTrack.create(trackerId).also {
    it.id = id
    it.manga_id = novelId
    it.remote_id = remoteId
    it.library_id = libraryId
    it.title = title
    it.last_chapter_read = lastChapterRead
    it.total_chapters = totalChapters
    it.status = status
    it.score = score
    it.tracking_url = remoteUrl
    it.started_reading_date = startDate
    it.finished_reading_date = finishDate
    it.private = private
}

/**
 * Adapt a [NovelTrack] to the manga-domain [DomainTrack] the reused tracking UI (TrackInfoDialogHome,
 * the selectors, `displayScore`) is typed against. The `mangaId` slot holds the `novelId`.
 */
fun NovelTrack.toUiTrack(): DomainTrack = toDbTrack().toDomainTrack(idRequired = false)!!

fun DbTrack.toNovelTrack(idRequired: Boolean = true): NovelTrack? {
    val trackId = id ?: if (!idRequired) -1 else return null
    return NovelTrack(
        id = trackId,
        novelId = manga_id,
        trackerId = tracker_id,
        remoteId = remote_id,
        libraryId = library_id,
        title = title,
        lastChapterRead = last_chapter_read,
        totalChapters = total_chapters,
        status = status,
        score = score,
        remoteUrl = tracking_url,
        startDate = started_reading_date,
        finishDate = finished_reading_date,
        private = private,
    )
}
