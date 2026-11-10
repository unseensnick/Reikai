package reikai.data.local

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.Downloader
import reikai.domain.download.renameDownloadFolder
import reikai.domain.library.ContentType
import reikai.domain.local.ChapterMove
import reikai.domain.local.LocalFileJournal
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalFileJournalPlan.Step
import reikai.novel.local.LocalNovelSource
import reikai.source.local.localVolumeChapter

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
            check(renameDownloadFolder(file, volume.to)) { "Could not rename ${volume.from}" }
            done += file to volume.from
            plan = plan.copy(
                volumes = plan.volumes.mapIndexed { i, step -> if (i == index) step.took(file) else step },
            )
            journal.update(id, plan)
        }
        series?.let {
            check(renameDownloadFolder(seriesFile, it.to)) { "Could not rename ${it.from}" }
            done += seriesFile to it.from
            journal.update(id, plan.copy(series = plan.series?.took(seriesFile)))
        }
    }

    suspend fun reverse() {
        done.asReversed().forEach { (file, name) -> renameDownloadFolder(file, name) }
        journalId?.let { journal.delete(it) }
    }

    private fun Step.took(file: UniFile) = copy(toUri = file.uri.toString())
}

/**
 * Each of a local series' chapter [urls] at its address once the series folder is [target] and each volume
 * in [volumeNames] (old to new) is renamed; the ones that do not change are left out. A loose book's one
 * volume is the series' own file, so it takes the series' new name. Rename files and its replay both read it.
 */
internal fun renamedChapterMoves(
    type: ContentType,
    urls: List<String>,
    folder: String,
    target: String,
    volumeNames: Map<String, String>,
): List<ChapterMove> = urls.map { url ->
    val moved = if (type == ContentType.NOVELS) {
        LocalNovelSource.parseChapterPath(url)?.let { (_, volume, key) ->
            val renamed = volumeNames[volume] ?: if (volume == folder) target else volume
            LocalNovelSource.chapterPath(target, renamed, key)
        } ?: url
    } else {
        localVolumeChapter(url)?.let { it.copy(series = target, volume = volumeNames[it.volume] ?: it.volume).url }
            ?: (listOf(target) + url.split('/').drop(1)).joinToString("/")
    }
    ChapterMove(url, moved)
}.filter { it.fromUrl != it.toUrl }

/** The name a case-only rename goes through, which a kill can leave a folder at. */
internal fun String.inFlightName() = this + Downloader.TMP_DIR_SUFFIX
