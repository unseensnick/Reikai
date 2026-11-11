package reikai.data.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import logcat.LogPriority
import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalFileJournal
import reikai.domain.local.LocalFileJournalPlan.FileStep
import reikai.domain.local.LocalFileJournalPlan.Organize
import reikai.domain.local.LocalFileJournalPlan.UrlRule
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeOwner
import reikai.novel.local.LocalNovelSource
import reikai.source.local.localChapterUnit
import reikai.storage.createNew
import reikai.storage.listOrNull
import reikai.storage.moveTo
import reikai.storage.parkIfSpent
import reikai.storage.rename
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import kotlin.coroutines.cancellation.CancellationException

/**
 * Runs an Organize files plan: written to the journal first, then its steps in order, the rows' one transaction
 * first and one provider move, rename or folder per file after it, each noted in the journal as it lands, so a
 * kill is finished or reversed at the next start ([replay]). A step that fails puts back the steps before it by
 * the same path; one whose putting back fails too leaves the journal row and refuses the series' folders until
 * that replay ([LocalSeriesLocks.block]), since its rows and files then disagree. Both content types.
 */
@Inject
class LocalOrganizer(
    private val context: Context,
    private val journal: LocalFileJournal,
    private val chapterMoves: LocalChapterMoveRepository,
    private val locks: LocalSeriesLocks,
) {

    /** Runs [plan] from its first step; the caller holds the lock on every folder it names. */
    suspend fun run(type: ContentType, plan: Organize): LocalMoveResult {
        val id = journal.insert(type, plan)
        return carryOn(type, id, plan)
    }

    /** Takes up [plan], journal row [id], where a kill left it, before any listing can read the half-done state. */
    suspend fun replay(type: ContentType, id: Long, plan: Organize) {
        val root = reach(plan.root) ?: return locks.block(type, plan.folders())
        // The step a kill cut off may have landed before its note did; one that cannot be told is left for a
        // later start, since going on or putting back would each be wrong for one of the two answers.
        val cut = plan.steps.getOrNull(plan.done)?.takeIf { it !is FileStep.Rows && it !is FileStep.SetAside }
        val landed = cut?.let { landed(root, it) ?: return locks.block(type, plan.folders()) }
        val at = if (landed == true) plan.copy(done = plan.done + 1).also { journal.update(id, it) } else plan
        val result = carryOn(type, id, at)
        if (result is LocalMoveResult.Failed) logcat(LogPriority.WARN, result.error) { "Organize $id left for later" }
    }

    private suspend fun carryOn(type: ContentType, id: Long, plan: Organize): LocalMoveResult {
        var at = plan
        val root = reach(plan.root) ?: return blocked(type, plan, IllegalStateException("The local folder is gone"))
        while (at.done < at.steps.size) {
            val step = at.steps[at.done]
            val next = at.copy(done = at.done + 1)
            try {
                if (step is FileStep.Rows) {
                    // The note goes in the rows' own transaction, so a kill leaves both or neither.
                    writeRows(type, plan, step.forward) { journal.update(id, next) }
                } else {
                    check(apply(root, step)) { "Could not $step" }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                return putBack(type, id, at, e)
            }
            at = next
            // A step that landed is put back with the rest when its note fails, never left out of them.
            if (step !is FileStep.Rows) {
                try {
                    journal.update(id, next)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return putBack(type, id, next, e)
                }
            }
        }
        journal.delete(id)
        return LocalMoveResult.Moved(emptyList())
    }

    private suspend fun putBack(type: ContentType, id: Long, plan: Organize, failure: Exception): LocalMoveResult {
        if (plan.isPutBack) return blocked(type, plan, failure)
        val back = plan.putBack()
        journal.update(id, back)
        val result = carryOn(type, id, back)
        return if (result is LocalMoveResult.Moved) LocalMoveResult.Failed(failure) else result
    }

    private fun blocked(type: ContentType, plan: Organize, failure: Exception): LocalMoveResult {
        locks.block(type, plan.folders())
        return LocalMoveResult.Failed(failure)
    }

    private suspend fun writeRows(type: ContentType, plan: Organize, forward: Boolean, note: suspend () -> Unit) {
        val from = if (forward) plan.fromSeries else plan.toSeries
        val to = if (forward) plan.toSeries else plan.fromSeries
        val keys = if (forward) plan.volumeKeys else plan.volumeKeys.entries.associate { (a, b) -> b to a }
        val rules = if (forward) {
            plan.urls
        } else {
            plan.urls.map {
                UrlRule(it.toFolder, it.toVolume, it.fromFolder, it.fromVolume)
            }
        }
        chapterMoves.organize(
            type,
            SeriesRename(plan.entryId, from, to, keys),
            remove = if (forward) plan.ownersBefore else plan.ownersAfter,
            add = if (forward) plan.ownersAfter else plan.ownersBefore,
            inTransaction = note,
        ) { organizedUrl(type, it, rules, from, to) }
    }

    private fun apply(root: UniFile, step: FileStep): Boolean = when (step) {
        is FileStep.Rows -> error("Rows are written, not applied")
        is FileStep.MakeFolder ->
            resolve(root, step.path.dropLast(1))?.createNew(context, step.path.last(), directory = true) != null
        is FileStep.SetAside -> {
            // Never deleted: a folder an organize made holds, once emptied, only what a relist wrote into it.
            resolve(root, step.path)?.parkIfSpent(context) { it.holdsOnlyDetails() }
            true
        }
        // A case-only rename a kill cut off waits at its in-flight name.
        is FileStep.Rename -> (
            resolve(
                root,
                step.path,
            ) ?: resolve(root, step.path.dropLast(1) + step.to.inFlightName())
            )
            ?.rename(step.to) == true
        is FileStep.Move -> {
            val from = resolve(root, step.path.dropLast(1))
            val into = resolve(root, step.into)
            val document = from?.findFile(step.path.last())
            if (from == null || into == null || document == null) {
                false
            } else {
                into.listOrNull(context)?.none { it.name == step.path.last() } == true &&
                    document.moveTo(context, from, into) != null
            }
        }
    }

    /** Whether [step] is through on disk, by the names its parents' trusted listings hold; null when unreadable. */
    private fun landed(root: UniFile, step: FileStep): Boolean? = when (step) {
        is FileStep.Rows, is FileStep.SetAside -> null
        is FileStep.MakeFolder -> holds(root, step.path.dropLast(1), step.path.last())
        is FileStep.Rename -> holds(root, step.path.dropLast(1), step.to)?.takeIf {
            holds(root, step.path.dropLast(1), step.path.last()) == false
        }
        is FileStep.Move -> holds(root, step.into, step.path.last())?.takeIf {
            holds(root, step.path.dropLast(1), step.path.last()) == false
        }
    }

    private fun holds(root: UniFile, folder: List<String>, name: String): Boolean? =
        resolve(root, folder)?.listOrNull(context)?.any { it.name == name }

    private fun resolve(root: UniFile, path: List<String>): UniFile? =
        path.fold(root as UniFile?) { dir, name -> dir?.findFile(name) }

    private fun reach(uri: String): UniFile? = UniFile.fromUri(context, Uri.parse(uri))?.takeIf { it.isDirectory }
}

/** Every series folder an organize names, before and after, which its holder locks and a failure blocks. */
fun Organize.folders(): List<String> =
    (listOf(fromSeries, toSeries) + (ownersBefore + ownersAfter).flatMap { listOf(it.folder, it.owner) }).distinct()

/** A local chapter address's folder and volume (a manga volume folder or [VolumeOwner.LOOSE], a novel's book). */
internal fun chapterVolumeUnit(type: ContentType, url: String): Pair<String, String>? = when (type) {
    ContentType.NOVELS -> LocalNovelSource.parseChapterPath(url)?.let { (series, book, _) -> series to book }
    else -> localChapterUnit(url)
}

/**
 * Chapter [url] at its address once [rules] have moved its volume, or, for any other volume of the series' own
 * folder [from], once that folder is [to]. Addresses of other folders no rule names stay as they are.
 */
internal fun organizedUrl(type: ContentType, url: String, rules: List<UrlRule>, from: String, to: String): String {
    val (folder, volume) = chapterVolumeUnit(type, url) ?: return url
    val rule = rules.firstOrNull { it.fromFolder == folder && it.fromVolume == volume }
        ?: UrlRule(from, volume, to, volume).takeIf { folder == from }
        ?: return url
    return when (type) {
        ContentType.NOVELS -> LocalNovelSource.parseChapterPath(url)?.let { (_, _, key) ->
            LocalNovelSource.chapterPath(rule.toFolder, rule.toVolume, key)
        } ?: url
        else -> {
            val file = url.split('/').last()
            listOfNotNull(rule.toFolder, rule.toVolume.takeIf { it != VolumeOwner.LOOSE }, file).joinToString("/")
        }
    }
}

/**
 * Whether a file left in a series folder is only its cover or details: the local source writes a ComicInfo.xml
 * into a series folder when it reads one from an archive, and a relist writes a cover.
 */
internal fun UniFile.holdsOnlyDetails(): Boolean {
    val name = this.name.orEmpty()
    return isFile && (
        name.startsWith("cover.", ignoreCase = true) || name.equals("details.json", ignoreCase = true) ||
            name == COMIC_INFO_FILE || name.startsWith(".")
        )
}
