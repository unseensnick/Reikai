package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import reikai.domain.library.ReleaseInterval
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import kotlin.time.Clock

@Inject
class FetchInterval(
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    suspend fun withFetchInterval(
        manga: Manga,
        dateTime: LocalDateTime,
        timeZone: TimeZone,
        window: Pair<Long, Long>,
    ): MangaUpdate {
        // RK --> keeping a user-set interval and reading (0, 0) as today are ReleaseInterval rules novels call too
        val interval = ReleaseInterval.userOrPredicted(manga.fetchInterval) {
            calculateInterval(
                chapters = getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true),
                zone = timeZone,
            )
        }
        val currentWindow =
            ReleaseInterval.windowOrToday(window, Clock.System.now().toLocalDateTime(timeZone).date, timeZone)
        // RK <--
        val nextUpdate = calculateNextUpdate(manga, interval, dateTime, timeZone, currentWindow)

        return MangaUpdate(manga.id) {
            this.nextUpdate = nextUpdate
            fetchInterval = interval
        }
    }

    // RK --> the date math lives in reikai.domain.library.ReleaseInterval, which novels call too
    fun getWindow(localDateTime: LocalDate, timeZone: TimeZone): Pair<Long, Long> =
        ReleaseInterval.window(localDateTime, timeZone)
    // RK <--

    // RK --> the date math lives in reikai.domain.library.ReleaseInterval, which novels call too
    internal fun calculateInterval(chapters: List<Chapter>, zone: TimeZone): Int =
        ReleaseInterval.calculate(chapters.map { it.dateUpload }, chapters.map { it.dateFetch }, zone)

    private fun calculateNextUpdate(
        manga: Manga,
        interval: Int,
        dateTime: LocalDateTime,
        timeZone: TimeZone,
        window: Pair<Long, Long>,
    ): Long = ReleaseInterval.nextUpdate(manga.nextUpdate, manga.lastUpdate, interval, dateTime, timeZone, window)
    // RK <--

    companion object {
        const val MAX_INTERVAL = ReleaseInterval.MAX_INTERVAL // RK
    }
}
