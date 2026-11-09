package reikai.data.local

import com.hippo.unifile.UniFile
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
suspend fun moveLocalFiles(copies: List<FileCopy>, commit: suspend () -> Unit): LocalMoveResult {
    val made = mutableListOf<UniFile>()
    try {
        copies.forEach { copy ->
            val dir = copy.folder?.let { name ->
                copy.parent.findFile(name) ?: checkNotNull(copy.parent.createDirectory(name)).also { made += it }
            } ?: copy.parent
            made += copyChecked(copy.source, dir, copy.name)
        }
        commit()
    } catch (e: Throwable) {
        made.forEach { it.delete() }
        if (e is CancellationException) throw e
        return LocalMoveResult.Failed(e)
    }
    return LocalMoveResult.Moved(copies.filterNot { it.source.delete() }.map { it.source.name.orEmpty() })
}

/** [source] copied into [dir] as [name], a folder with everything in it; a file checked by its length. */
private fun copyChecked(source: UniFile, dir: UniFile, name: String): UniFile {
    check(dir.findFile(name) == null) { "$name is already in ${dir.name}" }
    val target = if (source.isDirectory) dir.createDirectory(name) else dir.createFile(name)
    checkNotNull(target) { "Could not create $name in ${dir.name}" }
    try {
        if (source.isDirectory) {
            source.listFiles().orEmpty().forEach { copyChecked(it, target, it.name.orEmpty()) }
        } else {
            source.openInputStream().use { input -> target.openOutputStream().use { input.copyTo(it) } }
            check(target.length() == source.length()) { "$name was copied short" }
        }
    } catch (e: Throwable) {
        target.delete()
        throw e
    }
    return target
}
