package reikai.data.local

import android.content.Context
import android.net.Uri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import logcat.LogPriority
import reikai.domain.local.LocalFileJournal
import reikai.domain.local.LocalFileJournalEntry
import reikai.domain.local.LocalFileJournalPlan
import reikai.domain.local.LocalSeriesLocks
import reikai.storage.deleteFile
import reikai.storage.freeName
import reikai.storage.rename
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finishes or reverses, at start, an Organize files ([LocalOrganizer.replay]) or an import Replace a kill cut off,
 * under the series' lock and before any listing can read the half-done state: the locks open only once [replay] has
 * run. A replace promotes its whole copy when the old file has stepped aside, and otherwise keeps the old file; one
 * whose name another writer took meanwhile keeps both beside it. A row that fails, or whose folder cannot be reached,
 * holds its series as an organize does until a retry mends it, since an import of the same name meanwhile would take
 * the copy for stale. Nothing is touched before it is found where the plan says.
 */
@Inject
@SingleIn(AppScope::class)
class LocalFileJournalReplay(
    private val context: Context,
    private val journal: LocalFileJournal,
    private val locks: LocalSeriesLocks,
    private val organizer: LocalOrganizer,
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
            is LocalFileJournalPlan.Replace -> try {
                replayReplace(context, journal, entry.id, plan)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                // Left, the old book is in no listing, so the series waits on its repair as an organize's does.
                plan.seriesFolder()?.let { organizer.holdReplace(entry.type, entry.id, it) }
                throw e
            }
            is LocalFileJournalPlan.Organize -> locks.locked(entry.type, plan.folders()) {
                organizer.replay(entry.type, entry.id, plan)
            }
        }
    }
}

/**
 * Finishes or reverses import Replace row [id], [plan]: at start before the locks open, or under the series' lock when
 * a Replace left in this process is retried ([LocalOrganizer.holdUntilRepaired]).
 */
internal suspend fun replayReplace(
    context: Context,
    journal: LocalFileJournal,
    id: Long,
    plan: LocalFileJournalPlan.Replace,
) {
    val folder = UniFile.fromUri(context, Uri.parse(plan.folder))?.takeIf { it.exists() }
        ?: error("The folder of ${plan.name} cannot be reached")
    val name = folder.findFile(plan.name)
    val part = folder.findFile(plan.part)?.takeIf { it.uri.toString() == plan.partUri }
    val aside = folder.findFile(plan.aside)
    when {
        // Another writer took the name while the old book stood aside, so the replace never finished: the old
        // book and the copy both list beside that file, at free names as an import's Keep both gives them.
        name != null && part != null && aside != null -> {
            val taken = folder.listFiles().orEmpty().mapNotNullTo(HashSet()) { it.name }
            check(aside.rename(freeName(plan.name, taken))) { "Could not keep ${plan.aside}" }
            check(part.rename(freeName(plan.name, taken))) { "Could not keep ${plan.part}" }
        }
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

// A row written before [LocalFileJournalPlan.Replace.series] names its folder only by address, whose last segment
// ends in the folder's name for a file and for a storage document alike.
private fun LocalFileJournalPlan.Replace.seriesFolder() =
    series ?: Uri.parse(folder).lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
