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
import reikai.storage.rename
import tachiyomi.core.common.util.system.logcat
import kotlin.coroutines.cancellation.CancellationException

/**
 * Finishes or reverses, at start, an Organize files ([LocalOrganizer.replay]) or an import Replace a kill cut off,
 * under the series' lock and before any listing can read the half-done state: the locks open only once [replay] has
 * run. A replace promotes its whole copy when the old file has stepped aside, and otherwise keeps the old file. A
 * row whose folder cannot be reached is kept for a later start. Nothing is touched before it is found where the
 * plan says.
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
            is LocalFileJournalPlan.Replace -> replayReplace(entry.id, plan)
            is LocalFileJournalPlan.Organize -> locks.locked(entry.type, plan.folders()) {
                organizer.replay(entry.type, entry.id, plan)
            }
        }
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
}
