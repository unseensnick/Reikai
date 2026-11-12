package reikai.data.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.core.metro.AppCoroutineScope
import reikai.domain.library.ContentType
import reikai.domain.local.LocalChapterMoveRepository
import reikai.domain.local.LocalFileJournal
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalFileJournalPlan.FileStep
import reikai.domain.local.LocalFileJournalPlan.Organize
import reikai.domain.local.LocalFileJournalPlan.UrlRule
import reikai.domain.local.LocalSeriesLocks
import reikai.domain.local.SeriesRename
import reikai.domain.local.VolumeOwner
import reikai.novel.local.LocalNovelSource
import reikai.source.local.localChapterFile
import reikai.source.local.localChapterUnit
import reikai.source.local.localChapterUrl
import reikai.storage.createNew
import reikai.storage.listOrNull
import reikai.storage.moveTo
import reikai.storage.parkIfSpent
import reikai.storage.rename
import tachiyomi.core.common.util.system.logcat
import tachiyomi.core.metadata.comicinfo.COMIC_INFO_FILE
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Runs an Organize files plan: written to the journal first, then its steps in order, the rows' one transaction
 * first and one provider move, rename or folder per file after it, each noted in the journal as it lands, so a
 * kill is finished or reversed at the next start ([replay]). A step that fails puts back the steps before it by
 * the same path; one whose putting back fails too leaves the journal row and refuses the series' folders
 * ([LocalSeriesLocks.block]), since its rows and files then disagree, until a retry in this process or the next
 * start's replay resolves it. Both content types.
 */
@Inject
class LocalOrganizer(
    private val context: Context,
    private val journal: LocalFileJournal,
    private val chapterMoves: LocalChapterMoveRepository,
    private val locks: LocalSeriesLocks,
    @AppCoroutineScope private val scope: CoroutineScope,
) {

    /** The first wait before a blocked organize is taken up again; each later one doubles, up to [LAST_RETRY]. */
    internal var firstRetry: Duration = 2.seconds

    // Journal rows a retry is waiting on, so a block met again inside a retry does not start a second one.
    private val retrying = ConcurrentHashMap.newKeySet<Long>()

    // The folders each unresolved journal row refuses, by row, which is how [forget] finds the rows a series waits on.
    private val holding = ConcurrentHashMap<Long, Pair<ContentType, List<String>>>()

    /** Runs [plan] from its first step; the caller holds the lock on every folder it names. */
    suspend fun run(type: ContentType, plan: Organize): LocalMoveResult {
        // Before the journal, so a folder whose query hiccups fails the organize with nothing to put back.
        reach(plan.root) ?: return LocalMoveResult.Failed(IllegalStateException("The local folder cannot be read"))
        val id = journal.insert(type, plan)
        return carryOn(type, id, plan)
    }

    /** Takes up [plan], journal row [id], where a kill left it, before any listing can read the half-done state. */
    suspend fun replay(type: ContentType, id: Long, plan: Organize) {
        val root = reach(plan.root) ?: return block(type, id, plan)
        // The step a kill cut off may have landed before its note did; one that cannot be told is left for a
        // retry, since going on or putting back would each be wrong for one of the two answers.
        val cut = plan.steps.getOrNull(plan.done)?.takeIf { it !is FileStep.Rows && it !is FileStep.SetAside }
        val landed = cut?.let { landed(root, it) ?: return block(type, id, plan) }
        val at = if (landed == true) plan.copy(done = plan.done + 1).also { journal.update(id, it) } else plan
        val result = carryOn(type, id, at)
        if (result is LocalMoveResult.Failed) logcat(LogPriority.WARN, result.error) { "Organize $id left for later" }
    }

    private suspend fun carryOn(type: ContentType, id: Long, plan: Organize): LocalMoveResult {
        var at = plan
        val root = reach(plan.root) ?: return blocked(type, id, plan, IllegalStateException("The local folder is gone"))
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
        return LocalMoveResult.Moved
    }

    private suspend fun putBack(type: ContentType, id: Long, plan: Organize, failure: Exception): LocalMoveResult {
        if (plan.isPutBack) return blocked(type, id, plan, failure)
        val back = plan.putBack()
        // Unnoted, the row still reads as the forward run at [plan]'s step, which a retry or the next start resumes.
        try {
            journal.update(id, back)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return blocked(type, id, plan, failure)
        }
        val result = carryOn(type, id, back)
        return if (result is LocalMoveResult.Moved) LocalMoveResult.Failed(failure) else result
    }

    private fun blocked(type: ContentType, id: Long, plan: Organize, failure: Exception): LocalMoveResult {
        block(type, id, plan)
        return LocalMoveResult.Failed(failure)
    }

    private fun block(type: ContentType, id: Long, plan: Organize) = holdUntilRepaired(type, id, plan.folders()) {
        val left = it as? Organize ?: return@holdUntilRepaired
        locks.repairing(type, left.folders()) { replay(type, id, left) }
    }

    /** Holds series folder [series] until import Replace row [id], whose old book was left aside, is mended. */
    fun holdReplace(type: ContentType, id: Long, series: String) = holdUntilRepaired(type, id, listOf(series)) {
        val left = it as? LocalFileJournalPlan.Replace ?: return@holdUntilRepaired
        locks.repairing(type, listOf(series)) { replayReplace(context, journal, id, left) }
    }

    /**
     * Drops every unresolved journal row refusing any of [folders] and lets its folders be held again, moving no file,
     * so the next listing reads the files where they are. Under the rows' own repair hold, so a retry already running
     * finishes first. The user's way out of a row no retry can resolve.
     */
    suspend fun forget(type: ContentType, folders: Collection<String>) {
        val wanted = folders.mapTo(HashSet()) { it.lowercase() }
        holding.filter { (_, held) -> held.first == type && held.second.any { it.lowercase() in wanted } }
            .forEach { (id, held) ->
                locks.repairing(type, held.second) { journal.delete(id) }
                holding -= id
                locks.unblock(type, held.second)
            }
    }

    /**
     * Refuses [folders] and takes journal row [id] up again in this process through [repair], given the row as it
     * stands, after a wait that doubles each time it is still unresolved, up to [LAST_RETRY]; the folders open again
     * once the row is gone. Any journal row whose files and rows disagree is held this way: an organize's, and an
     * import Replace whose old book was left aside.
     */
    private fun holdUntilRepaired(
        type: ContentType,
        id: Long,
        folders: List<String>,
        repair: suspend (LocalFileJournalPlan) -> Unit,
    ) {
        locks.block(type, folders)
        holding[id] = type to folders
        if (!retrying.add(id)) return
        scope.launch {
            try {
                var wait = firstRetry
                while (true) {
                    delay(wait)
                    // The journal reads too, so a database hiccup costs one round rather than the retry.
                    val resolved = try {
                        journal.all().firstOrNull { it.id == id }?.plan?.let { repair(it) }
                        journal.all().none { it.id == id }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        logcat(LogPriority.WARN, e) { "Retrying local file journal row $id failed" }
                        false
                    }
                    if (resolved) break
                    wait = (wait * 2).coerceAtMost(LAST_RETRY)
                }
                holding -= id
                locks.unblock(type, folders)
            } finally {
                retrying -= id
            }
        }
    }

    private suspend fun writeRows(type: ContentType, plan: Organize, forward: Boolean, note: suspend () -> Unit) {
        val from = if (forward) plan.fromSeries else plan.toSeries
        val to = if (forward) plan.toSeries else plan.fromSeries
        val keys = if (forward) plan.volumeKeys else plan.volumeKeys.entries.associate { (a, b) -> b to a }
        val rules = if (forward) plan.urls else plan.urls.map { it.reversed() }
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
        is FileStep.Rename -> {
            val parent = step.path.dropLast(1)
            // A case-only rename a kill cut off waits at its in-flight name, which the step itself takes up.
            moved(root, parent, step.path.last(), parent, step.to) ?: false.takeIf {
                holds(root, parent, step.to.inFlightName()) == true
            }
        }
        is FileStep.Move -> moved(root, step.path.dropLast(1), step.path.last(), step.into, step.path.last())
    }

    /** Whether a document went from [from] in [fromFolder] to [to] in [toFolder]: null when not plainly one or the other. */
    private fun moved(
        root: UniFile,
        fromFolder: List<String>,
        from: String,
        toFolder: List<String>,
        to: String,
    ): Boolean? {
        val there = holds(root, toFolder, to) ?: return null
        val left = holds(root, fromFolder, from) ?: return null
        return when {
            there && !left -> true
            !there && left -> false
            else -> null
        }
    }

    private fun holds(root: UniFile, folder: List<String>, name: String): Boolean? =
        resolve(root, folder)?.listOrNull(context)?.any { it.name == name }

    private fun resolve(root: UniFile, path: List<String>): UniFile? =
        path.fold(root as UniFile?) { dir, name -> dir?.findFile(name) }

    private fun reach(uri: String): UniFile? = UniFile.fromUri(context, Uri.parse(uri))?.takeIf { it.isDirectory }

    private companion object {
        val LAST_RETRY = 5.minutes
    }
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
    val file = localChapterFile(url)
    val rule = rules.firstOrNull {
        it.fromFolder == folder && it.fromVolume == volume && (it.file == null || it.file == file)
    }
        ?: UrlRule(from, volume, to, volume).takeIf { folder == from }
        ?: return url
    return when (type) {
        ContentType.NOVELS -> LocalNovelSource.parseChapterPath(url)?.let { (_, _, key) ->
            LocalNovelSource.chapterPath(rule.toFolder, rule.toVolume, key)
        } ?: url
        else -> localChapterUrl(rule.toFolder, rule.toVolume, file)
    }
}

/** A loose book's one volume is the book itself, so it takes the new name along with its folder. */
internal fun looseBookRules(type: ContentType, from: String, to: String): List<UrlRule> =
    if (isLooseBook(type, from)) listOf(UrlRule(from, from, to, to)) else emptyList()

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
