package reikai.data.local

import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.SeriesRename
import reikai.storage.IN_FLIGHT_SUFFIX

/**
 * The row step of [rename], the relink's path: the repository reads every chapter address the series holds, a
 * row's or a kept state's, and takes each through [organizedUrl], the rule Organize files moves them by.
 */
internal suspend fun LocalChapterMoveRepository.renameSeries(
    type: ContentType,
    rename: SeriesRename,
) = rename(type, rename) {
    organizedUrl(type, it, looseBookRules(type, rename.fromSeries, rename.toSeries), rename.fromSeries, rename.toSeries)
}

/** The name a case-only rename goes through, which a kill can leave a folder at. */
internal fun String.inFlightName() = this + IN_FLIGHT_SUFFIX
