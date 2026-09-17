package reikai.data.novel

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import reikai.domain.library.ReleaseInterval
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Predicts when [novel] is next due from all of its chapters and stores it, the novel side of manga's
 * `UpdateManga.awaitUpdateFetchInterval`. An interval the user set is kept; a [window] of (0, 0) means
 * today's.
 */
suspend fun updateNovelFetchInterval(
    novel: Novel,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    window: Pair<Long, Long> = Pair(0, 0),
    zone: TimeZone = TimeZone.currentSystemDefault(),
    now: LocalDateTime = Clock.System.now().toLocalDateTime(zone),
) {
    val interval = novel.fetchInterval.takeIf { it < 0 } ?: run {
        val chapters = novelChapterRepository.getByNovelId(novel.id)
        ReleaseInterval.calculate(chapters.map { it.dateUpload }, chapters.map { it.dateFetch }, zone)
    }
    val currentWindow = if (window.first == 0L &&
        window.second == 0L
    ) {
        ReleaseInterval.window(now.date, zone)
    } else {
        window
    }
    val nextUpdate = ReleaseInterval.nextUpdate(novel.nextUpdate, novel.lastUpdate, interval, now, zone, currentWindow)
    novelRepository.update(NovelUpdate(id = novel.id, nextUpdate = nextUpdate, fetchInterval = interval))
}

/** When this novel is next due, or null once it is completed, as manga's `Manga.expectedNextUpdate`. */
fun Novel.expectedNextUpdate(): Instant? =
    nextUpdate.takeIf { status != NovelStatusCode.COMPLETED.toLong() }?.let(Instant::fromEpochMilliseconds)
