package reikai.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import reikai.storage.copyVerified
import reikai.storage.deleteFile
import reikai.storage.deleteIfEmpty
import reikai.storage.deleteTree
import kotlin.coroutines.cancellation.CancellationException

/** A file or folder of a local series, copied as [name] into [parent], or into its [folder] when one is named. */
data class FileCopy(val source: UniFile, val parent: UniFile, val folder: String?, val name: String)

sealed interface LocalMoveResult {
    /** Moved; [leftBehind] names the originals that could not be deleted, each still a full copy. */
    data class Moved(val leftBehind: List<String>) : LocalMoveResult

    data class Failed(val error: Throwable) : LocalMoveResult
}

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
    return LocalMoveResult.Moved(
        placed.filterNot { (source, copy) ->
            deleteMoved(context, source, copy)
        }.map { it.first.name.orEmpty() },
    )
}

/**
 * Deletes [source] now that [copy] holds it: a folder child by child, only those the copy has, and the folder
 * itself only once a fresh listing, trusted whole, finds nothing left but hidden markers. A picked folder's
 * listing can come back short without failing, and a whole-folder delete would then take what was never copied.
 */
private fun deleteMoved(context: Context, source: UniFile, copy: UniFile): Boolean {
    if (!source.isDirectory) return source.deleteFile()
    val copied = copy.listFiles().orEmpty().associateBy { it.name }
    source.listFiles().orEmpty().forEach { child -> copied[child.name]?.let { deleteMoved(context, child, it) } }
    return source.deleteIfEmpty(context)
}
