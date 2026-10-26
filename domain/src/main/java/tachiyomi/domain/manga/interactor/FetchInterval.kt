package tachiyomi.domain.manga.interactor

import dev.zacsweers.metro.Inject
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import reikai.domain.library.ChapterDates
import reikai.domain.library.ReleaseInterval
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.MangaUpdate
import kotlin.time.Clock
import kotlin.time.Instant

@Inject
class FetchInterval(
    private val getChaptersByMangaId: GetChaptersByMangaId,
) {

    /**
     * Works out the entry's fetch interval and the next day to check it for new chapters.
     *
     * @param foundRelease whether the check that led here found new chapters.
     */
    suspend fun withFetchInterval(
        manga: Manga,
        dateTime: LocalDateTime,
        timeZone: TimeZone,
        window: ClosedRange<Instant>?,
        foundRelease: Boolean = false,
    ): MangaUpdate {
        // RK --> the prediction lives in reikai.domain.library.ReleaseInterval, which novels call too
        val currentWindow =
            ReleaseInterval.windowOrToday(window, Clock.System.now().toLocalDateTime(timeZone).date, timeZone)
        val (interval, nextUpdate) = ReleaseInterval.predict(
            manga.fetchInterval,
            manga.nextUpdate,
            manga.lastUpdate,
            dateTime,
            timeZone,
            currentWindow,
            foundRelease,
        ) {
            getChaptersByMangaId.await(manga.id, applyScanlatorFilter = true)
                .let { chapters -> ChapterDates(chapters.map { it.dateUpload }, chapters.map { it.dateFetch }) }
        }
        // RK <--

        return MangaUpdate(manga.id) {
            this.nextUpdate = nextUpdate
            fetchInterval = interval // RK
        }
    }

    // RK --> the date math lives in reikai.domain.library.ReleaseInterval, which novels call too
    fun getWindow(localDateTime: LocalDate, timeZone: TimeZone): ClosedRange<Instant> =
        ReleaseInterval.window(localDateTime, timeZone)
    // RK <--

    companion object {
        const val MAX_INTERVAL = ReleaseInterval.MAX_INTERVAL // RK
    }
}

// RK: ReleaseSchedule.kt and ReleasePattern.kt moved to reikai.domain.library, shared with novels
