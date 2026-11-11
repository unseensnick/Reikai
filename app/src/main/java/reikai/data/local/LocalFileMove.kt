package reikai.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import reikai.storage.copyVerified
import reikai.storage.deleteFile
import reikai.storage.deleteTree
import reikai.storage.freeNames
import reikai.storage.listOrNull
import reikai.storage.rename
import kotlin.coroutines.cancellation.CancellationException

/** A file or folder of a local series, copied as [name] into [parent], or into its [folder] when one is named. */
data class FileCopy(val source: UniFile, val parent: UniFile, val folder: String?, val name: String)

sealed interface LocalMoveResult {
    /**
     * Moved; [leftBehind] names the originals that could not be deleted, each still a full copy, and [parked]
     * the emptied source folders set aside under a hidden name for [purgeParked] to take later.
     */
    data class Moved(val leftBehind: List<String>, val parked: List<UniFile> = emptyList()) : LocalMoveResult

    data class Failed(val error: Throwable) : LocalMoveResult
}

/** The hidden name a moved folder waits under once its copied files are gone, until [purgeParked] reads it again. */
const val PARKED_PREFIX = ".moved-"

/**
 * Moves a local series' files in three phases so a failure partway loses nothing: every copy is made and
 * checked, then [commit] writes the database, and only after it are the originals deleted. A copy or a
 * commit that fails deletes what was copied and leaves the series as it was. Both content types call it.
 */
suspend fun moveLocalFiles(context: Context, copies: List<FileCopy>, commit: suspend () -> Unit): LocalMoveResult {
    val made = mutableListOf<UniFile>()
    val placed = mutableListOf<Pair<UniFile, UniFile>>()
    try {
        copies.forEach { copy ->
            val dir = copy.folder?.let { name ->
                copy.parent.findFile(name) ?: checkNotNull(copy.parent.createDirectory(name)).also { made += it }
            } ?: copy.parent
            val target = dir.copyVerified(context, copy.source, copy.name)
            made += target
            placed += copy.source to target
        }
        commit()
    } catch (e: Throwable) {
        made.forEach { it.deleteTree() }
        if (e is CancellationException) throw e
        return LocalMoveResult.Failed(e)
    }
    val parked = mutableListOf<UniFile>()
    val leftBehind = placed.filterNot { (source, copy) ->
        when {
            source.isFile -> source.deleteFile()
            source.isDirectory -> {
                removeCopied(copy, source)
                park(source)?.also { parked += it } != null
            }
            else -> false
        }
    }
    return LocalMoveResult.Moved(leftBehind.map { it.first.name.orEmpty() }, parked)
}

/**
 * Deletes the files of [source] that [copy] holds, folder by folder; the folders themselves stay. A picked
 * folder's listing can come back short without failing, and the same lie in the copy's listing and here would
 * make a whole-folder delete take what was never copied, so no folder is deleted on this listing's word.
 */
private fun removeCopied(copy: UniFile, source: UniFile) {
    val copied = copy.listFiles().orEmpty().associateBy { it.name }
    source.listFiles().orEmpty().forEach { child ->
        val twin = copied[child.name] ?: return@forEach
        when {
            child.isFile -> child.deleteFile()
            child.isDirectory -> removeCopied(twin, child)
        }
    }
}

/** [source] under a hidden name where it stood, so no listing reads it as a chapter; null when it would not rename. */
private fun park(source: UniFile): UniFile? {
    val name = source.name ?: return null
    return freeNames(PARKED_PREFIX + name).take(PARK_ATTEMPTS).firstOrNull { source.rename(it) }?.let { source }
}

private const val PARK_ATTEMPTS = 5

/**
 * Deletes each of [parked] only once a fresh trusted listing, level by level, finds nothing in it but hidden
 * marker files: a second reading at a later moment is a different oracle from the one the move copied by, so
 * a file the first listing hid is found and kept, hidden beside the series, never deleted.
 */
fun purgeParked(context: Context, parked: List<UniFile>) {
    parked.forEach { if (it.holdsNothing(context)) it.deleteTree() }
}

private fun UniFile.holdsNothing(context: Context): Boolean = listOrNull(context)?.all {
    (it.isFile && it.name.orEmpty().startsWith('.')) || (it.isDirectory && it.holdsNothing(context))
} == true
