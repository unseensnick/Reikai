package reikai.data.local

import android.content.Context
import com.hippo.unifile.UniFile
import reikai.storage.copyVerified
import reikai.storage.createNew
import reikai.storage.deleteTree
import reikai.storage.list
import reikai.storage.retireMoved
import kotlin.coroutines.cancellation.CancellationException

/**
 * A file or folder of a local series, copied as [name] into [parent], or into its [folder] when one is named,
 * without the entries of a source folder's own level named in [leaving], which stay with the original.
 */
data class FileCopy(
    val source: UniFile,
    val parent: UniFile,
    val folder: String?,
    val name: String,
    val leaving: Set<String> = emptySet(),
)

sealed interface LocalMoveResult {
    /** Moved; [leftBehind] names the originals that could not be cleared away, each still standing where it was. */
    data class Moved(val leftBehind: List<String>) : LocalMoveResult

    data class Failed(val error: Throwable) : LocalMoveResult
}

/**
 * Moves a local series' files in three phases so a failure partway loses nothing: every copy is made and
 * checked, then [commit] writes the database, and only after it are the originals cleared away, a file deleted
 * and a folder set aside under a hidden name, never deleted ([retireMoved]). A copy or a commit that fails
 * deletes what was copied and leaves the series as it was. Both content types call it.
 */
suspend fun moveLocalFiles(context: Context, copies: List<FileCopy>, commit: suspend () -> Unit): LocalMoveResult {
    val made = mutableListOf<UniFile>()
    val placed = mutableListOf<Pair<UniFile, UniFile>>()
    try {
        copies.forEach { copy ->
            // Found by the parent's trusted listing, never its own query: a folder that query missed would be
            // "made" again as the one already there, and deleted with all it holds if the move then failed.
            val dir = copy.folder?.let { name ->
                copy.parent.list(context).firstOrNull { it.name == name }
                    ?: copy.parent.createNew(context, name, directory = true).also { made += it }
            } ?: copy.parent
            val target = dir.copyVerified(context, copy.source, copy.name, copy.leaving)
            made += target
            placed += copy.source to target
        }
        commit()
    } catch (e: Throwable) {
        made.forEach { it.deleteTree() }
        if (e is CancellationException) throw e
        return LocalMoveResult.Failed(e)
    }
    // Named before it is cleared away, which renames a folder.
    val leftBehind = placed.mapNotNull { (source, copy) ->
        source.name.orEmpty().takeUnless { source.retireMoved(copy) }
    }
    return LocalMoveResult.Moved(leftBehind)
}
