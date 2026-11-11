package reikai.data.local

import com.hippo.unifile.UniFile
import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalFileJournal
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalFileJournalPlan.Step
import reikai.domain.local.SeriesRename
import reikai.novel.local.LocalNovelSource
import reikai.source.local.localVolumeChapter
import reikai.storage.IN_FLIGHT_SUFFIX
import reikai.storage.rename

/**
 * The disk half of Rename files, written to the [journal] before the first rename and kept up to date with
 * each document's uri as it takes its new name, so a kill before the row step is repaired at the next start
 * ([LocalFileJournalReplay]). [renameOnDisk] throws at the first rename that fails and [reverse] puts back
 * what took, as the row step's failure needs too; the row step deletes the journal row in its own transaction.
 */
internal class JournaledRename(
    private val journal: LocalFileJournal,
    private val type: ContentType,
    private val entryId: Long,
    private val parent: UniFile,
    private val seriesFile: UniFile,
    private val series: LocalFileNames.Rename?,
    private val volumes: List<LocalFileNames.Rename>,
) {
    var journalId: Long? = null
        private set

    private var plan: LocalFileJournalPlan.Rename? = null
    private val done = mutableListOf<Pair<UniFile, String>>()

    suspend fun renameOnDisk() {
        val files = volumes.map { checkNotNull(seriesFile.findFile(it.from)) { "${it.from} is gone" } }
        var plan = LocalFileJournalPlan.Rename(
            parent = parent.uri.toString(),
            folder = seriesFile.name.orEmpty(),
            entryId = entryId,
            series = series?.let { Step(it.from, it.to, seriesFile.uri.toString()) },
            volumes = volumes.zip(files) { volume, file -> Step(volume.from, volume.to, file.uri.toString()) },
        )
        val id = journal.insert(type, plan)
        journalId = id
        this.plan = plan
        volumes.zip(files).forEachIndexed { index, (volume, file) ->
            check(file.rename(volume.to)) { "Could not rename ${volume.from}" }
            done += file to volume.from
            plan = plan.copy(
                volumes = plan.volumes.mapIndexed { i, step -> if (i == index) step.took(file) else step },
            )
            journal.update(id, plan)
        }
        series?.let {
            check(seriesFile.rename(it.to)) { "Could not rename ${it.from}" }
            done += seriesFile to it.from
            journal.update(id, plan.copy(series = plan.series?.took(seriesFile)))
        }
    }

    suspend fun reverse() {
        done.asReversed().forEach { (file, name) -> file.rename(name) }
        journalId?.let { journal.delete(it) }
    }

    private fun Step.took(file: UniFile) = copy(toUri = file.uri.toString())
}

/**
 * The row step of [rename], Rename files', its replay's and the relink's one path: the repository reads every
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
): String = if (type == ContentType.NOVELS) {
    LocalNovelSource.parseChapterPath(url)?.let { (_, volume, key) ->
        val renamed = volumeNames[volume] ?: if (volume == folder) target else volume
        LocalNovelSource.chapterPath(target, renamed, key)
    } ?: url
} else {
    localVolumeChapter(url)?.let { it.copy(series = target, volume = volumeNames[it.volume] ?: it.volume).url }
        ?: (listOf(target) + url.split('/').drop(1)).joinToString("/")
}

/** The name a case-only rename goes through, which a kill can leave a folder at. */
internal fun String.inFlightName() = this + IN_FLIGHT_SUFFIX
