package reikai.domain.novel.track

import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.track.model.toDomainTrack
import reikai.domain.novel.model.NovelTrack
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack
import tachiyomi.domain.track.model.Track as DomainTrack

/**
 * Bridge between [NovelTrack] and the mutable [DbTrack] the tracker services operate on, through the
 * manga [DomainTrack] and Mihon's own `toDbTrack` / `toDomainTrack`, so a field upstream adds reaches
 * novels at the one mapping that renames `novelId`. The `mangaId` slot carries the `novelId` purely to
 * round-trip it; novel persistence never touches `manga_track`.
 */
fun NovelTrack.toDbTrack(): DbTrack = toUiTrack().toDbTrack()

/**
 * Adapt a [NovelTrack] to the manga-domain [DomainTrack] the reused tracking UI (TrackInfoDialogHome,
 * the selectors, `displayScore`) is typed against. The `mangaId` slot holds the `novelId`.
 */
fun NovelTrack.toUiTrack(): DomainTrack = DomainTrack(
    id = id,
    mangaId = novelId,
    trackerId = trackerId,
    remoteId = remoteId,
    libraryId = libraryId,
    title = title,
    lastChapterRead = lastChapterRead,
    totalChapters = totalChapters,
    status = status,
    score = score,
    remoteUrl = remoteUrl,
    startDate = startDate,
    finishDate = finishDate,
    private = private,
)

fun DbTrack.toNovelTrack(idRequired: Boolean = true): NovelTrack? = toDomainTrack(idRequired)?.let {
    NovelTrack(
        id = it.id,
        novelId = it.mangaId,
        trackerId = it.trackerId,
        remoteId = it.remoteId,
        libraryId = it.libraryId,
        title = it.title,
        lastChapterRead = it.lastChapterRead,
        totalChapters = it.totalChapters,
        status = it.status,
        score = it.score,
        remoteUrl = it.remoteUrl,
        startDate = it.startDate,
        finishDate = it.finishDate,
        private = it.private,
    )
}
