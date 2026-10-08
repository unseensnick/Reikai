package reikai.data.novel

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import reikai.domain.library.ReleaseInterval
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Predicts when [novel] is next due from all of its [chapters] and stores it, the novel side of manga's
 * `UpdateManga.awaitUpdateFetchInterval`. An interval the user set is kept, and then [chapters] is never
 * read; a [window] of (0, 0) means today's.
 */
suspend fun updateNovelFetchInterval(
    novel: Novel,
    chapters: suspend () -> List<NovelChapter>,
    novelRepository: NovelRepository,
    window: Pair<Long, Long> = Pair(0, 0),
    zone: TimeZone = TimeZone.currentSystemDefault(),
    now: LocalDateTime = Clock.System.now().toLocalDateTime(zone),
) {
    val interval = ReleaseInterval.userOrPredicted(novel.fetchInterval) {
        val all = chapters()
        ReleaseInterval.calculate(all.map { it.dateUpload }, all.map { it.dateFetch }, zone)
    }
    val currentWindow = ReleaseInterval.windowOrToday(window, now.date, zone)
    val nextUpdate = ReleaseInterval.nextUpdate(novel.nextUpdate, novel.lastUpdate, interval, now, zone, currentWindow)
    novelRepository.update(
        NovelUpdate(novel.id) {
            this.nextUpdate = nextUpdate
            fetchInterval = interval
        },
    )
}

/**
 * Manga's sync-time prediction rule, run once after a whole-novel sync. [novel] is the snapshot from
 * before the sync, whose `lastUpdate` the prediction counts from. As manga's sync, an unchanged list
 * still moves a prediction that was forced, never made, or has fallen behind [window].
 */
suspend fun predictNovelFetchInterval(
    novel: Novel,
    listChanged: Boolean,
    manualFetch: Boolean,
    novelChapterRepository: NovelChapterRepository,
    novelRepository: NovelRepository,
    window: Pair<Long, Long> = Pair(0, 0),
) {
    if (listChanged || ReleaseInterval.needsPrediction(manualFetch, novel.fetchInterval, novel.nextUpdate, window)) {
        updateNovelFetchInterval(novel, { novelChapterRepository.getByNovelId(novel.id) }, novelRepository, window)
    }
}

/**
 * When this novel is next due, or null once it is completed: twin of `Manga.expectedNextUpdate`, pinned by
 * EntryUpdateTwinsConformanceTest.
 */
fun Novel.expectedNextUpdate(): Instant? =
    nextUpdate.takeIf { status != NovelStatusCode.COMPLETED.toLong() }?.let(Instant::fromEpochMilliseconds)
