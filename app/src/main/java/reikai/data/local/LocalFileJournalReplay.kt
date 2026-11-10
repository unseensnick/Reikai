package reikai.data.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalFileJournal
import reikai.domain.local.LocalFileJournalEntry
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalFileJournalPlan.Step
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.SeriesRename
import reikai.domain.novel.NovelChapterRepository
import reikai.storage.deleteFile
import reikai.storage.rename
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.chapter.repository.ChapterRepository
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finishes or reverses, at start, a Rename files or import Replace a kill cut off between its disk step and
 * its row step, under the series' lock and before any listing can read the half-done state: the locks open
 * only once [replay] has run. A rename whose every step took on disk gets its row step; one that took in part
 * is renamed back, as a failed rename is. A replace promotes its whole copy when the old file has stepped
 * aside. A row whose folder cannot be reached is kept for a later start. Nothing is touched before it has
 * been found where, and as, the plan says.
 */
@Inject
@SingleIn(AppScope::class)
class LocalFileJournalReplay(
    private val context: Context,
    private val journal: LocalFileJournal,
    private val locks: LocalSeriesLocks,
    private val chapterMoves: LocalChapterMoveRepository,
    private val chapterRepository: ChapterRepository,
    private val novelChapterRepository: NovelChapterRepository,
) {

    suspend fun replay() {
        try {
            journal.all().forEach { entry ->
                try {
                    replay(entry)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    logcat(LogPriority.ERROR, e) { "Replaying local file journal row ${entry.id} failed" }
                }
            }
        } finally {
            locks.open()
        }
    }

    private suspend fun replay(entry: LocalFileJournalEntry) {
        when (val plan = entry.plan) {
            is LocalFileJournalPlan.Rename -> locks.locked(entry.type, plan.folder) {
                replayRename(entry.id, entry.type, plan)
            }
            is LocalFileJournalPlan.Replace -> replayReplace(entry.id, plan)
        }
    }

    private suspend fun replayRename(id: Long, type: ContentType, plan: LocalFileJournalPlan.Rename) {
        val parent = reach(plan.parent) ?: return
        val series = plan.series?.let { parent.find(it) }
        val seriesFile = series?.file ?: parent.findFile(plan.folder)
        if (seriesFile == null) {
            journal.delete(id)
            return
        }
        val volumes = plan.volumes.map { seriesFile.find(it) }
        val steps = volumes + listOfNotNull(series)
        if (steps.all { it is Found.NotYet }) {
            journal.delete(id)
            return
        }
        if (steps.none { it is Found.NotYet }) {
            steps.forEach { if (it is Found.InFlight) check(it.file.rename(it.step.to)) }
            val urls = when (type) {
                ContentType.NOVELS -> novelChapterRepository.getByNovelId(plan.entryId).map { it.url }
                else -> chapterRepository.getChapterByMangaId(plan.entryId).map { it.url }
            }
            val target = plan.series?.to ?: plan.folder
            val volumeNames = plan.volumes.associate { it.from to it.to }
            val moves = renamedChapterMoves(type, urls, plan.folder, target, volumeNames)
            chapterMoves.rename(type, SeriesRename(plan.entryId, plan.folder, target, volumeNames), moves, id)
            return
        }
        steps.asReversed().forEach { found -> found.file?.let { it.rename(found.step.from) } }
        journal.delete(id)
    }

    private suspend fun replayReplace(id: Long, plan: LocalFileJournalPlan.Replace) {
        val folder = reach(plan.folder) ?: return
        val name = folder.findFile(plan.name)
        val part = folder.findFile(plan.part)?.takeIf { it.uri.toString() == plan.partUri }
        val aside = folder.findFile(plan.aside)
        when {
            name != null -> {
                part?.deleteFile()
                aside?.deleteFile()
            }
            part != null -> {
                check(part.rename(plan.name)) { "Could not promote ${plan.part}" }
                aside?.deleteFile()
            }
            aside != null -> check(aside.rename(plan.name)) { "Could not put back ${plan.aside}" }
        }
        journal.delete(id)
    }

    private fun reach(uri: String): UniFile? = UniFile.fromUri(context, Uri.parse(uri))?.takeIf { it.exists() }

    /** What [step] came to on disk, by the names it goes from and to and the uri it took. */
    private fun UniFile.find(step: Step): Found {
        val from = findFile(step.from)
        val to = findFile(step.to)
        val sameName = step.from.equals(step.to, ignoreCase = true)
        return when {
            // A case-only rename finds the same file under both names; its new spelling says whether it took.
            sameName && to != null -> if (to.name == step.to) Found.Took(step, to) else Found.NotYet(step)
            to != null && (from == null || to.uri.toString() == step.toUri) -> Found.Took(step, to)
            from != null -> Found.NotYet(step)
            else -> findFile(step.to.inFlightName())?.let { Found.InFlight(step, it) } ?: Found.NotYet(step)
        }
    }

    /** [file] is the document where the step left it: at its new name, or at the in-flight one; null before it took. */
    private sealed interface Found {
        val step: Step
        val file: UniFile?

        data class NotYet(override val step: Step) : Found {
            override val file: UniFile? get() = null
        }

        data class Took(override val step: Step, override val file: UniFile) : Found
        data class InFlight(override val step: Step, override val file: UniFile) : Found
    }
}
