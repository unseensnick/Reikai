package reikai.data.dedupe

import com.hippo.unifile.UniFile
import eu.kanade.tachiyomi.data.download.Downloader
import eu.kanade.tachiyomi.util.storage.DiskUtil
import logcat.LogPriority
import reikai.domain.download.DownloadIndexRules
import reikai.domain.download.hasRoomToCopy
import tachiyomi.core.common.util.system.logcat

/** What carrying one merged-away copy's folder did: whether a later run has anything left to try, and a change. */
internal data class FolderCarry(val finished: Boolean, val changed: Boolean)

/**
 * Merges a merged-away copy's download folder into the survivor's, one chapter at a time, by copy and delete, since
 * storage cannot move a file between folders. A chapter the survivor lacks is copied under [COPY_SUFFIX], checked
 * against its source, renamed into place, and only then deleted from the source. A chapter the survivor has is left
 * in both, and nothing is ever overwritten. Rules: docs/dev/plans/mihon-schema-rewrite.md.
 */
internal object DownloadFolderMerge {

    /** Ends in the downloaders' suffix, so neither index lists a copy in progress; no download name ends this way. */
    const val COPY_SUFFIX = "_merge" + Downloader.TMP_DIR_SUFFIX

    fun merge(from: UniFile, into: UniFile): FolderCarry {
        // A copy an interrupted run left behind, whose source it never deleted
        into.listFiles().orEmpty().filter { it.name.orEmpty().endsWith(COPY_SUFFIX) }.forEach { it.delete() }
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

        val results = chapters.map { move(it, into) }
        val placed = results.count { it == Moved.PLACED }
        // Only a folder this run emptied goes: a listing that failed reads as empty too
        if (placed == entries.size && placed > 0 && from.listFiles()?.isEmpty() == true) {
            from.delete()
        } else if (placed > 0 || results.any { it == Moved.LEFT }) {
            logcat { "Merged ${from.name} into ${into.name}; the chapters the survivor already had stay in it" }
        }
        return FolderCarry(finished = Moved.FAILED !in results, changed = placed > 0)
    }

    private enum class Moved { PLACED, LEFT, FAILED }

    private fun move(chapter: UniFile, into: UniFile): Moved {
        val name = chapter.name ?: return Moved.FAILED
        // A downloaded chapter folder always holds pages, so an empty listing is a failed read, not a chapter
        if (chapter.isDirectory && chapter.listFiles().isNullOrEmpty()) return Moved.LEFT
        val tempName = name + COPY_SUFFIX
        val copy = runCatching { copyOf(chapter, into, tempName) }
            .onFailure { logcat(LogPriority.WARN, it) { "Copying $name into ${into.name} failed" } }
            .getOrNull()
        if (copy == null || !isSameTree(chapter, copy)) {
            into.findFile(tempName)?.delete()
            return Moved.FAILED
        }
        // A download for the survivor may have written the name since the listing; the storage then renames to a
        // free "name (1)" instead, so the rename is judged by the name it produced
        if (into.findFile(name) != null) {
            copy.delete()
            return Moved.LEFT
        }
        if (!copy.renameTo(name) || copy.name != name) {
            copy.delete()
            return Moved.FAILED
        }
        if (!chapter.delete()) logcat(LogPriority.WARN) { "Merged $name, but its old copy could not be deleted" }
        return Moved.PLACED
    }

    private fun copyOf(source: UniFile, parent: UniFile, name: String): UniFile {
        if (source.isDirectory) {
            val dir = checkNotNull(parent.createDirectory(name)) { "No folder $name" }
            source.listFiles().orEmpty().forEach { copyOf(it, dir, checkNotNull(it.name)) }
            return dir
        }
        val file = checkNotNull(parent.createFile(name)) { "No file $name" }
        source.openInputStream().use { input -> file.openOutputStream().use { input.copyTo(it) } }
        return file
    }

    /** The same names at every level and every file the same length; a content hash would read it all again. */
    private fun isSameTree(source: UniFile, copy: UniFile): Boolean {
        if (source.isDirectory != copy.isDirectory) return false
        if (!source.isDirectory) return source.length() == copy.length()
        val sources = source.listFiles().orEmpty()
        val copies = copy.listFiles().orEmpty().associateBy { it.name }
        return sources.isNotEmpty() && sources.size == copies.size &&
            sources.all { entry -> copies[entry.name]?.let { isSameTree(entry, it) } == true }
    }

    private fun sizeOf(file: UniFile): Long =
        if (file.isDirectory) file.listFiles().orEmpty().sumOf(::sizeOf) else file.length()
}
