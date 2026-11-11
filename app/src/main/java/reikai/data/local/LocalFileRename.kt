package reikai.data.local

import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.SeriesRename
import reikai.novel.local.LocalNovelSource
import reikai.source.local.localVolumeChapter
import reikai.storage.IN_FLIGHT_SUFFIX

/**
 * The row step of [rename], the old Rename files replay's and the relink's one path: the repository reads every
 * chapter address the series holds, a row's or a kept state's, and takes each through [renamedChapterUrl].
 */
internal suspend fun LocalChapterMoveRepository.renameSeries(
    type: ContentType,
    rename: SeriesRename,
    journalId: Long? = null,
) = rename(type, rename, journalId) { renamedChapterUrl(type, it, rename.fromSeries, rename.toSeries, rename.volumes) }

/**
 * A local series' chapter [url] at its address once the series folder is [target] and each volume in
 * [volumeNames] (old to new) is renamed. A loose book's one volume is the series' own file, so it takes the
 * series' new name.
 */
internal fun renamedChapterUrl(
    type: ContentType,
    url: String,
    folder: String,
    target: String,
    volumeNames: Map<String, String>,
): String = when {
    // A chapter a group lists from another folder is not in the folder renamed.
    chapterVolumeUnit(type, url)?.first != folder -> url
    type == ContentType.NOVELS -> LocalNovelSource.parseChapterPath(url)?.let { (_, volume, key) ->
        val renamed = volumeNames[volume] ?: if (volume == folder) target else volume
        LocalNovelSource.chapterPath(target, renamed, key)
    } ?: url
    else -> localVolumeChapter(url)?.let { it.copy(series = target, volume = volumeNames[it.volume] ?: it.volume).url }
        ?: (listOf(target) + url.split('/').drop(1)).joinToString("/")
}

/** The name a case-only rename goes through, which a kill can leave a folder at. */
internal fun String.inFlightName() = this + IN_FLIGHT_SUFFIX
