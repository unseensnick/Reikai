package reikai.data.dedupe

import android.content.Context
import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import reikai.domain.download.DownloadIndexRules
import reikai.domain.download.hasRoomToCopy
import reikai.storage.IN_FLIGHT_SUFFIX
import reikai.storage.copyVerified
import reikai.storage.deleteTree
import reikai.storage.parkIfSpent
import reikai.storage.rename
import reikai.storage.retireMoved
import tachiyomi.core.common.util.system.logcat

/** What carrying one merged-away copy's folder did: whether a later run has anything left to try, and a change. */
internal data class FolderCarry(val finished: Boolean, val changed: Boolean)

/**
 * Merges a merged-away copy's download folder into the survivor's, one chapter at a time, by copy and delete, since
 * storage cannot move a file between folders. A chapter the survivor lacks is copied under [COPY_SUFFIX] from a
 * trusted listing and checked against one, renamed into place, and only then cleared from the source: a chapter
 * file is deleted, a chapter folder set aside under a hidden name neither index reads, never deleted, since a
 * download can be the only copy left, and so is the source folder once the run has emptied it. A chapter the
 * survivor has is left in both, and nothing is ever overwritten. Rules: docs/dev/subsystems/data-and-backup.md.
 */
internal object DownloadFolderMerge {

    /** Ends in the downloaders' suffix, so neither index lists a copy in progress; no download name ends this way. */
    const val COPY_SUFFIX = "_merge$IN_FLIGHT_SUFFIX"

    fun merge(context: Context, from: UniFile, into: UniFile): FolderCarry {
        // A copy an interrupted run left behind, whose source it never deleted
        into.listFiles().orEmpty().filter { it.name.orEmpty().endsWith(COPY_SUFFIX) }.forEach { it.deleteTree() }
        val kept = into.listFiles().orEmpty().mapNotNullTo(HashSet()) { it.name?.lowercase() }
        val entries = from.listFiles().orEmpty()
        // A half-written download is no chapter yet, and a name the survivor has is its own download of the chapter
        val chapters = entries.filter { entry ->
            val name = entry.name ?: return@filter false
            DownloadIndexRules.isIndexed(name) && name.lowercase() !in kept
        }
        val bytes = chapters.sumOf(::sizeOf)
        if (!hasRoomToCopy(DiskUtil.getAvailableStorageSpace(into), bytes)) {
            logcat(LogPriority.WARN) { "No room to merge ${from.name} into ${into.name}: $bytes bytes" }
            return FolderCarry(finished = false, changed = false)
        }

        val results = chapters.map { move(context, it, into) }
        val placed = results.count { it == Moved.PLACED }
        // Only a folder this run emptied is set aside, and only once a trusted listing shows it spent
        if (placed == entries.size && placed > 0) {
            from.parkIfSpent(context)
        } else if (placed > 0 || results.any { it == Moved.LEFT }) {
            logcat { "Merged ${from.name} into ${into.name}; the chapters the survivor already had stay in it" }
        }
        return FolderCarry(finished = Moved.FAILED !in results, changed = placed > 0)
    }

    private enum class Moved { PLACED, LEFT, FAILED }

    private fun move(context: Context, chapter: UniFile, into: UniFile): Moved {
        val name = chapter.name ?: return Moved.FAILED
        // A downloaded chapter folder always holds pages, so an empty listing is a failed read, not a chapter
        if (chapter.isDirectory && chapter.listFiles().isNullOrEmpty()) return Moved.LEFT
        val tempName = name + COPY_SUFFIX
        val copy = runCatching { into.copyVerified(context, chapter, tempName) }
            .onFailure { logcat(LogPriority.WARN, it) { "Copying $name into ${into.name} failed" } }
            .getOrNull() ?: return Moved.FAILED
        // A download for the survivor may have written the name since the listing
        if (into.findFile(name) != null) {
            copy.deleteTree()
            return Moved.LEFT
        }
        if (!copy.rename(name)) {
            copy.deleteTree()
            return Moved.FAILED
        }
        if (!chapter.retireMoved(copy)) logcat(LogPriority.WARN) { "Merged $name, but its old copy still stands" }
        return Moved.PLACED
    }

    private fun sizeOf(file: UniFile): Long =
        if (file.isDirectory) file.listFiles().orEmpty().sumOf(::sizeOf) else file.length()
}
