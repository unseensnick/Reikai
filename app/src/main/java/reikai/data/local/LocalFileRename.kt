package reikai.data.local

import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalFileJournalPlan.UrlRule
import reikai.domain.local.SeriesRename
import reikai.storage.IN_FLIGHT_SUFFIX

/**
 * The row step of [rename], the relink's path: the repository reads every chapter address the series holds, a
 * row's or a kept state's, and takes each through [organizedUrl], the rule Organize files moves them by, with each
 * renamed volume of [SeriesRename.volumes] (old name to new) as a rule of its own.
 */
internal suspend fun LocalChapterMoveRepository.renameSeries(
    type: ContentType,
    rename: SeriesRename,
) {
    val (from, to) = rename.fromSeries to rename.toSeries
    val rules = rename.volumes.map { (old, new) -> UrlRule(from, old, to, new) } + looseBookRules(type, from, to)
    rename(type, rename) { organizedUrl(type, it, rules, from, to) }
}

/** The name a case-only rename goes through, which a kill can leave a folder at. */
internal fun String.inFlightName() = this + IN_FLIGHT_SUFFIX
